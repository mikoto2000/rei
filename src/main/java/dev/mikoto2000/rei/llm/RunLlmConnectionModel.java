package dev.mikoto2000.rei.llm;

import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** Dispatches each inference using the host-owned accepted connection. */
final class RunLlmConnectionModel implements ChatModel {
  RunLlmConnectionModel(Function<RunLlmConnection, ChatModel> models,
      Consumer<RunLlmConnection> verify) {}

  @Override
  public ChatResponse call(Prompt prompt) { return null; }

  @Override
  public Flux<ChatResponse> stream(Prompt prompt) { return Flux.empty(); }
}
