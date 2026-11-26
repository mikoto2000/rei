package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** Decoration must capture the accepted connection before cache eviction or global selection. */
class PinnedRequestDecorationTest {
  private RunLlmConnection pin(String name) {
    return new RunLlmConnection(
        name,
        true,
        LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE,
        null,
        null,
        new ResolvedLlmEndpoint(
            name,
            1,
            LlmApiType.CHAT_COMPLETIONS,
            "http://fixture.invalid/v1",
            "chat-endpoint:" + name,
            "model-" + name,
            new LlmCapabilities(ReasoningReplayMode.NONE, null, Set.of()),
            null,
            null,
            Duration.ofSeconds(2)),
        Map.of(),
        Map.of());
  }

  @Test
  void cachedDecorationReceivesTheExactAcceptedConnectionOncePerCreation() {
    var seen = new CopyOnWriteArrayList<RunLlmConnection>();
    try (var models =
        new PinnedChatModels(
            pin -> null,
            pin -> {},
            null,
            1,
            (pin, model) -> {
              seen.add(pin);
              return model;
            })) {
      var first = pin("first");
      var second = pin("second");
      var held = models.model(first);
      assertThat(models.model(first)).isSameAs(held);
      models.model(second);
      assertThat(models.model(first)).isNotSameAs(held);
      assertThat(seen).containsExactly(first, second, first);
      assertThat(held.getOptions().getModel()).isEqualTo("model-first");
    }
  }

  @Test
  void lateAdmissionUsesTheHeldConnectionWithoutLookingUpCredentialsOrCurrentSelection() {
    var credentials = new AtomicInteger();
    var admitted = new CopyOnWriteArrayList<RunLlmConnection>();
    try (var models =
        new PinnedChatModels(
            pin -> {
              credentials.incrementAndGet();
              return "PRIVATE";
            },
            pin -> {},
            null,
            1,
            (pin, model) ->
                new PlanRequestChatModel(
                    model,
                    () -> {
                      admitted.add(pin);
                      throw new IllegalStateException("fixture admission denied");
                    }))) {
      var first = pin("first");
      ChatModel held = models.model(first);
      models.model(pin("second"));
      assertThat(held).isInstanceOf(PlanRequestChatModel.class);
      assertThatThrownBy(() -> held.stream(new Prompt("fixture")).blockLast(Duration.ofSeconds(2)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("fixture admission denied");
      assertThat(admitted).containsExactly(first);
      assertThat(credentials).hasValue(0);
    }
  }
}
