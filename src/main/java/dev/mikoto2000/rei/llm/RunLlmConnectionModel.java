package dev.mikoto2000.rei.llm;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;
import reactor.core.publisher.Flux;

/** Dispatches each inference using the host-owned accepted connection. */
final class RunLlmConnectionModel implements ChatModel {
  private final Function<RunLlmConnection, ChatModel> models;
  private final Consumer<RunLlmConnection> verify;

  RunLlmConnectionModel(
      Function<RunLlmConnection, ChatModel> models, Consumer<RunLlmConnection> verify) {
    this.models = Objects.requireNonNull(models);
    this.verify = Objects.requireNonNull(verify);
  }

  @Override
  public ChatResponse call(Prompt prompt) {
    return resolve(prompt).call(prompt);
  }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) {
    return Flux.defer(() -> resolve(prompt).stream(prompt));
  }

  private ChatModel resolve(Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options))
      throw new IllegalStateException("Accepted Run connection is unavailable");
    var context = options.getToolContext();
    if (context == null
        || !(context.get(dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY)
            instanceof RunLlmConnection connection))
      throw new IllegalStateException("Accepted Run connection is unavailable");
    if (!Objects.equals(options.getModel(), connection.endpoint().model()))
      throw new IllegalStateException("Request model differs from accepted Run model");
    boolean matchingApi =
        switch (connection.endpoint().apiType()) {
          case CHAT_COMPLETIONS -> options instanceof OpenAiChatOptions;
          case RESPONSES -> options instanceof OpenAiResponsesChatOptions;
        };
    if (!matchingApi) throw new IllegalStateException("Request API differs from accepted Run API");
    if (!connection
        .endpoint()
        .equals(context.get(dev.mikoto2000.rei.modeloptions.ModelOptions.ENDPOINT_CONTEXT_KEY)))
      throw new IllegalStateException(
          "Run request endpoint context differs from accepted connection");
    boolean override;
    if (options instanceof OpenAiChatOptions chat) {
      override =
          overrides(
                  connection,
                  chat.getBaseUrl(),
                  chat.getApiKey(),
                  chat.getCredential(),
                  chat.getCustomHeaders(),
                  chat.getOrganizationId())
              || transportOverrides(
                  connection,
                  chat.getMaxRetries(),
                  chat.getTimeout(),
                  chat.getProxy(),
                  chat.getDeploymentName(),
                  chat.getMicrosoftFoundryServiceVersion(),
                  chat.isMicrosoftFoundry())
              || chat.isGitHubModels();
    } else {
      var responses = (OpenAiResponsesChatOptions) options;
      override =
          overrides(
                  connection,
                  responses.getBaseUrl(),
                  responses.getApiKey(),
                  responses.getCredential(),
                  responses.getCustomHeaders(),
                  responses.getOrganizationId())
              || transportOverrides(
                  connection,
                  responses.getMaxRetries(),
                  responses.getTimeout(),
                  responses.getProxy(),
                  responses.getDeploymentName(),
                  responses.getMicrosoftFoundryServiceVersion(),
                  responses.isMicrosoftFoundry());
    }
    if (override)
      throw new IllegalStateException("Run request cannot override accepted connection");
    verify.accept(connection);
    if (context.get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY)
        instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)
      execution.preserveInferenceProgress(connection);
    return Objects.requireNonNull(models.apply(connection), "Run transport is unavailable");
  }

  private static boolean overrides(
      RunLlmConnection connection,
      String baseUrl,
      String apiKey,
      Object credential,
      java.util.Map<String, String> headers,
      String organization) {
    return baseUrl != null && !baseUrl.equals(connection.endpoint().baseUrl())
        || apiKey != null
        || credential != null
        || headers != null && !headers.isEmpty()
        || organization != null;
  }

  private static boolean transportOverrides(
      RunLlmConnection connection,
      int retries,
      java.time.Duration timeout,
      Object proxy,
      String deployment,
      Object version,
      boolean cloud) {
    return retries != 0
        || proxy != null
        || deployment != null
        || version != null
        || cloud
        || timeout != null
            && (timeout.isNegative()
                || timeout.isZero()
                || timeout.compareTo(connection.requestTimeout()) > 0);
  }
}
