package dev.mikoto2000.rei.llm;

import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.ai.chat.model.ChatModel;

/** Bounded native models sharing a host-owned transport, with no ambient connection lookup. */
final class PinnedChatModels implements AutoCloseable {
  PinnedChatModels(Function<RunLlmConnection, String> credential,
      Consumer<RunLlmConnection> verify, dev.mikoto2000.rei.llm.capture.CaptureStore captures,
      int capacity) {}

  ChatModel model(RunLlmConnection connection) {
    throw new IllegalStateException("Pinned native model factory is unavailable");
  }

  @Override
  public void close() {}
}
