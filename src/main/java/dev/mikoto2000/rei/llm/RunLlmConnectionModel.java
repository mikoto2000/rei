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

  RunLlmConnectionModel(Function<RunLlmConnection, ChatModel> models,
      Consumer<RunLlmConnection> verify) {
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
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)
        || options.getToolContext() == null
        || !(options.getToolContext().get(
            dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY)
            instanceof RunLlmConnection connection))
      throw new IllegalStateException("Accepted Run connection is unavailable");
    if (!Objects.equals(options.getModel(), connection.endpoint().model()))
      throw new IllegalStateException("Request model differs from accepted Run model");
    boolean matchingApi = switch (connection.endpoint().apiType()) {
      case CHAT_COMPLETIONS -> options instanceof OpenAiChatOptions;
      case RESPONSES -> options instanceof OpenAiResponsesChatOptions;
    };
    if (!matchingApi) throw new IllegalStateException("Request API differs from accepted Run API");
    verify.accept(connection);
    return Objects.requireNonNull(models.apply(connection), "Run transport is unavailable");
  }
}
