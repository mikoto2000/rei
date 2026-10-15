package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@Tag("integration")
class SlackAgentToolsTest {
  @TempDir Path dir;

  SlackAgentProperties settings() {
    var p = new SlackAgentProperties();
    p.setEnabled(true);
    p.setAllowedWorkspaceIds(Set.of("T1"));
    p.setAllowedChannelIds(Set.of("C1"));
    p.setAgents(Map.of("tenten", new SlackAgentProperties.Agent("T1", "U2", "B2", "C1", "U2", "")));
    return p;
  }

  RunExecutionContext execution(int calls) {
    var e =
        new RunExecutionContext(
            "run",
            new OutputLimitRunBudget(1, calls),
            new ProgressEvaluator(dir, null),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    e.setRunContext(new AgentRunContext("run", "session", dir, "project"));
    return e;
  }

  ToolContext context(RunExecutionContext e) {
    return new ToolContext(Map.of(RunExecutionContext.KEY, e, "toolCallId", "call"));
  }

  SlackAgentRepository repo() {
    return new SlackAgentRepository(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("storage.db")), Clock.systemUTC());
  }

  @Test
  void policyAuthorizesResolvedDestinationAndExplicitTextBeforeCreation() {
    var r = repo();
    var guard = mock(ToolPermissionGuard.class);
    var t = new SlackAgentTools(settings(), r, guard);
    var result =
        t.askExternalAgent("tenten", "question", "expected", 300, null, context(execution(3)));
    assertEquals("CREATED", result.status());
    assertFalse(result.requestId().isBlank());
    verify(guard).check(eq("askExternalAgent"), contains("C1"), any());
    assertEquals(1, r.forRun("run").size());
  }

  @Test
  void deniedPermissionHasNoRequestOrSend() {
    var r = repo();
    var guard = mock(ToolPermissionGuard.class);
    doThrow(new ToolPermissionException("askExternalAgent", PermissionDecision.DENY))
        .when(guard)
        .check(eq("askExternalAgent"), anyString(), any());
    var t = new SlackAgentTools(settings(), r, guard);
    assertThrows(
        ToolPermissionException.class,
        () -> t.askExternalAgent("tenten", "q", "", 300, null, context(execution(2))));
    assertTrue(r.forRun("run").isEmpty());
  }

  @Test
  void exhaustedRunAndInvalidDestinationCannotCreateRequest() {
    var r = repo();
    var guard = mock(ToolPermissionGuard.class);
    var t = new SlackAgentTools(settings(), r, guard);
    assertThrows(
        IllegalStateException.class,
        () -> t.askExternalAgent("tenten", "q", "", 300, null, context(execution(0))));
    var p = settings();
    p.setAllowedChannelIds(Set.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new SlackAgentTools(p, r, guard)
                .askExternalAgent("tenten", "q", "", 300, null, context(execution(2))));
    assertTrue(r.forRun("run").isEmpty());
    verifyNoInteractions(guard);
  }

  @Test
  void secretPatternsAreRemovedAndNoSessionHistoryIsAttached() {
    var r = repo();
    var t = new SlackAgentTools(settings(), r, mock(ToolPermissionGuard.class));
    var e = execution(3);
    e.setConversationSnapshot(
        List.of(new org.springframework.ai.chat.messages.UserMessage("PRIVATE SESSION HISTORY")));
    var result =
        t.askExternalAgent(
            "tenten",
            "token " + String.join("-", "xoxb", "123456789", "123456789", "AbCdEfGhIjKlMn"),
            "",
            300,
            null,
            context(e));
    var request = r.get(result.requestId());
    assertFalse(request.question().contains("xoxb-"));
    assertFalse(request.question().contains("PRIVATE SESSION HISTORY"));
  }

  @Test
  void networkWriteCapabilityCannotBeRelabeledAsRead() {
    assertEquals(
        Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
        ToolPermissionPolicy.intrinsicCapabilities("askExternalAgent"));
  }
}
