package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

/** Host-owned common connection selection, Run snapshots and inference resources. */
@org.springframework.stereotype.Component
public final class ChatConnectionService implements AutoCloseable, dev.mikoto2000.rei.modeloptions.ChatModelSelection {
  private final ChatEndpointCatalog catalog;
  private final int defaultOutputTokens;
  private final java.util.function.BiFunction<
          ToolCallingChatOptions, AgentRunContext, ToolCallingChatOptions>
      bindRunOptions;
  private final LegacyChatConnectionSource legacy;
  private final java.util.function.Supplier<String> legacyModel;
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
    this.defaultOutputTokens = properties.getMaxOutputTokens();
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
    this.legacyModel =
        configuredModel == null || configuredModel.isBlank() ? holder::get : () -> configuredModel;
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

  /** Nonsecret current selection; observing it never dispatches inference. */
  public Selection selection() {
    var selected = connections.selection();
    var model = selected.models().get(selected.endpoint());
    if (model == null) {
      model =
          catalog.profiles().stream()
              .filter(profile -> profile.id().equals(selected.endpoint()))
              .findFirst()
              .orElseThrow()
              .model();
      if ((model == null || model.isBlank()) && !catalog.hasNamedProfiles())
        model = legacyModel.get();
    }
    return new Selection(selected.endpoint(), model);
  }

  /** Public endpoint projection; no URL, credential reference or native options. */
  public record Endpoint(
      ChatEndpointCatalog.Profile profile,
      boolean active,
      ReasoningReplayMode reasoningReplay,
      String authenticationState,
      String unavailableReason,
      java.util.Map<String, Object> generationSettings,
      java.util.Map<String, String> unappliedSettings,
      java.util.List<String> diagnostics) {
    public Endpoint {
      java.util.Objects.requireNonNull(profile, "profile");
      generationSettings = RunGenerationSettings.copy(generationSettings);
      unappliedSettings = java.util.Map.copyOf(unappliedSettings);
      diagnostics = java.util.List.copyOf(diagnostics);
    }

    @Override
    public java.util.Map<String, Object> generationSettings() {
      return java.util.Collections.unmodifiableMap(
          new java.util.LinkedHashMap<>(generationSettings));
    }
  }

  public java.util.List<Endpoint> endpoints() {
    var selected = connections.selection();
    return catalog.profiles().stream()
        .map(configured -> {
          var model = selected.models().getOrDefault(configured.id(), configured.model());
          if ((model == null || model.isBlank()) && !catalog.hasNamedProfiles())
            model = legacyModel.get();
          var capabilities =
              java.util.Objects.equals(model, configured.model())
                  ? configured.capabilities()
                  : configured.capabilities().forUnconfirmedModel();
          var profile =
              new ChatEndpointCatalog.Profile(
                  configured.id(),
                  model,
                  configured.provider(),
                  configured.api(),
                  configured.authentication(),
                  configured.accountRef(),
                  configured.generation(),
                  configured.usagePath(),
                  capabilities);
          var source = catalog.connection(profile.id());
          var applied = new java.util.LinkedHashMap<String, Object>();
          var unapplied = new java.util.LinkedHashMap<String, String>();
          var reasons = new java.util.ArrayList<String>();
          var diagnostics = new java.util.ArrayList<>(catalog.diagnostics());
          var outputKey =
              profile.api() == LlmApiType.RESPONSES ? "max_output_tokens" : "max_tokens";
          if (capabilities.effective().get(LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS)
              == LlmEndpointCapabilities.Support.SUPPORTED) {
            applied.put(
                outputKey,
                source.getMaxOutputTokens() == null
                    ? defaultOutputTokens
                    : source.getMaxOutputTokens());
          } else {
            unapplied.put(outputKey, "unsupported or unconfirmed");
            if (source.getMaxOutputTokens() != null)
              reasons.add("max-output-tokens is unsupported or unconfirmed");
            else diagnostics.add("Inherited max-output-tokens omitted: unsupported or unconfirmed");
          }
          if (source.getTemperature() != null) {
            if (capabilities.effective().get(LlmEndpointCapabilities.Capability.TEMPERATURE)
                == LlmEndpointCapabilities.Support.SUPPORTED)
              applied.put("temperature", source.getTemperature());
            else {
              unapplied.put("temperature", "unsupported or unconfirmed");
              reasons.add("temperature is unsupported or unconfirmed");
            }
          }
          var replay =
              profile.api() == LlmApiType.CHAT_COMPLETIONS
                  ? ReasoningReplayMode.NONE
                  : source.getReasoningReplay();
          if (replay == null) reasons.add("reasoning-replay must be configured for Responses");
          var authenticationState =
              switch (profile.authentication()) {
                case NONE -> "not-required";
                case API_KEY -> "configured";
                case CHATGPT_OAUTH -> "unverified";
                case UNCONFIGURED -> "unconfigured";
                case LEGACY -> "legacy";
              };
          if (profile.authentication() == LlmAuthentication.UNCONFIGURED)
            reasons.add("Authentication is not configured");
          if (profile.provider() == LlmProvider.CHATGPT_PLAN)
            reasons.add("ChatGPT account registration requires verification");
          if (catalog.hasNamedProfiles() && profile.api() == LlmApiType.RESPONSES)
            reasons.add("Named Responses inference policy is not enabled");
          return new Endpoint(
              profile,
              profile.id().equals(selected.endpoint()),
              replay,
              authenticationState,
              reasons.isEmpty() ? null : String.join("; ", reasons),
              applied,
              unapplied,
              diagnostics);
        })
        .toList();
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
