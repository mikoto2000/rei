package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

/** Host-owned common connection selection, Run snapshots and inference resources. */
@org.springframework.stereotype.Component
public final class ChatConnectionService implements AutoCloseable {
  private final ChatEndpointCatalog catalog;
  private final java.util.function.BiFunction<
          ToolCallingChatOptions, AgentRunContext, ToolCallingChatOptions>
      bindRunOptions;
  private final LegacyChatConnectionSource legacy;
  private final RunLlmConnectionFactory factory;
  private final ChatRunConnections connections;
  private final PinnedChatModels modelCache;
  private final ChatModel routed;
  private final java.util.concurrent.ConcurrentMap<ClientKey, ChatClient> clients =
      new java.util.concurrent.ConcurrentHashMap<>();

  private record ClientKey(AgentRunContext.Mode mode, LlmApiType api) {}

  public ChatConnectionService(
      ChatEndpointCatalog catalog,
      LlmProperties properties,
      LlmModelProvider models,
      ModelHolderService holder,
      ObjectProvider<OpenAiConnectionDetails> details,
      ObjectProvider<OpenAiCommonProperties> common,
      ObjectProvider<OpenAiChatProperties> chat,
      ObjectProvider<dev.mikoto2000.rei.llm.capture.CaptureStore> captures) {
    this.catalog = catalog;
    this.bindRunOptions = models::bindRunOptions;
    this.legacy =
        new LegacyChatConnectionSource(
            models,
            properties,
            () -> {
              var commonProperties = common.getIfAvailable();
              var chatProperties = chat.getIfAvailable();
              if (commonProperties == null || chatProperties == null)
                throw new IllegalStateException(
                    "Legacy OpenAI connection settings are unavailable");
              var connectionDetails = details.getIfAvailable();
              return connectionDetails == null
                  ? org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil
                      .resolveCommonProperties(commonProperties, chatProperties)
                  : org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil
                      .resolveCommonProperties(connectionDetails, commonProperties, chatProperties);
            },
            () -> System.getenv("OPENAI_API_KEY"));
    var configuredModel = catalog.hasNamedProfiles() ? null : catalog.profiles().getFirst().model();
    this.factory =
        RunLlmConnectionFactory.withLegacySettings(
            catalog,
            properties,
            selected ->
                legacy.resolve(
                    selected == null && (configuredModel == null || configuredModel.isBlank())
                        ? holder.get()
                        : selected),
            account -> null);
    this.connections = new ChatRunConnections(catalog, factory);
    this.modelCache =
        new PinnedChatModels(
            this::credential,
            factory::verifyBinding,
            captures.getIfAvailable(),
            32,
            models::decorateRunChatModel);
    this.routed = new RunLlmConnectionModel(modelCache::model, factory::verifyBinding);
  }

  public RunLlmConnection accept(AgentRunContext owner) {
    return connections.accept(owner);
  }

  public RunLlmConnection require(AgentRunContext owner) {
    return connections.require(owner);
  }

  public ToolCallingChatOptions options(AgentRunContext owner) {
    var options = RunNativeOptions.options(require(owner));
    var context =
        new java.util.HashMap<>(
            java.util.Objects.requireNonNull(
                options.getToolContext(), "Accepted Run request context is unavailable"));
    context.put(AgentRunContext.class.getName(), owner);
    return bindRunOptions.apply(
        options.mutate().toolContext(null).toolContext(context).build(), owner);
  }

  public void restore(AgentRunContext owner, RunLlmConnection saved) {
    connections.restore(owner, saved);
  }

  public void release(AgentRunContext owner) {
    connections.release(owner);
  }

  public void select(String endpoint) {
    connections.select(endpoint);
  }

  public void selectModel(String model) {
    connections.selectModel(model);
  }

  public ChatModel model() {
    return routed;
  }

  public ChatClient client(
      LlmChatClientProvider provider, AgentRunContext.Mode mode, RunLlmConnection connection) {
    return clients.computeIfAbsent(
        new ClientKey(mode, connection.endpoint().apiType()),
        key -> provider.runChatClient(key.mode(), key.api(), routed));
  }

  private String credential(RunLlmConnection connection) {
    factory.verifyBinding(connection);
    if (!connection.named()) return legacy.credential(connection);
    if (connection.authentication() != LlmAuthentication.API_KEY)
      throw new IllegalStateException("Accepted inference credentials are unavailable");
    return catalog.connection(connection.profileId()).getApiKey();
  }

  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    clients.clear();
    modelCache.close();
  }
}
