package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.llm.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Local SQLite round trip only; no Slack or model connection. */
class SlackPinnedContinuationTest {
  @TempDir Path directory;

  SlackAgentContinuationStore store() {
    return new SlackAgentContinuationStore(new DriverManagerDataSource(
        "jdbc:sqlite:" + directory.resolve("continuations.db")));
  }

  AgentRunContext owner() {
    return new AgentRunContext("run", "session", directory, "project");
  }

  @Test
  void restartAndHumanGuidanceRetainTheAcceptedConnection() {
    var endpoint = new ResolvedLlmEndpoint("local", 1, LlmApiType.CHAT_COMPLETIONS,
        "http://local.invalid/v1", "chat-endpoint:local", "model",
        new LlmCapabilities(ReasoningReplayMode.NONE, 36, Set.of()), 512, null,
        Duration.ofSeconds(30));
    var pin = new RunLlmConnection("local", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null, endpoint, Map.of(), Map.of("max_tokens", 512));
    store().save(owner(), "request", List.of(new UserMessage("request")),
        new OutputLimitRunBudget.Snapshot(1, 2, 100, false), true, pin);
    var reopened = store();
    assertEquals(pin, reopened.get(owner()).connection());
    reopened.appendGuidance(owner(), List.of(new UserMessage("continue")));
    assertEquals(pin, store().get(owner()).connection());
    assertEquals(2, store().get(owner()).messages().size());
    assertEquals(2, store().get(owner()).budget().llmCalls());
    assertTrue(store().document("run").managedParent());
    assertThrows(IllegalArgumentException.class,
        () -> store().get(new AgentRunContext("run", "foreign", directory, "project")));
  }

  @Test
  void legacyContinuationDoesNotInventAConnection() {
    store().save(owner(), "request", List.of(new UserMessage("request")), null);
    assertNull(store().get(owner()).connection());
  }
}
