package dev.mikoto2000.rei.core.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class CheckpointAdvisorTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void checkpointIsInjectedIntoUserMessage() {
    CheckpointStore store = new CheckpointStore(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    store.save(new TurnCheckpoint("task-1", "UserService の Optional 対応", "step-2", List.of(), List.of(), List.of(),
        "再現テストが失敗した", null, "UserService.save() から再開", "LENGTH", "2026-08-17T00:00:00Z"));

    CheckpointAdvisor advisor = new CheckpointAdvisor(store);
    UserMessage userMessage = UserMessage.builder().text("続けて").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains("## Resume Checkpoint"));
    assertTrue(text.contains("UserService の Optional 対応"));
    assertTrue(text.contains("UserService.save() から再開"));
    assertTrue(text.contains("続けて"));
  }

  @Test
  void emptyCheckpointLeavesMessageUnchanged() {
    CheckpointStore store = new CheckpointStore(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    CheckpointAdvisor advisor = new CheckpointAdvisor(store);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  private static final class NoopAdvisorChain implements AdvisorChain {
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
