package dev.mikoto2000.rei.slack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunService;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.chat.ProjectRunQueue;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.ErrorInformation;
import dev.mikoto2000.rei.event.MessageCompletedPayload;
import dev.mikoto2000.rei.event.ToolFailedPayload;
import java.nio.file.Path;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("integration")
class SlackRuntimeTest {
  @TempDir Path root;
  private SlackStore store;
  private SlackProperties p;
  private ProjectRegistry projects;
  private SessionLifecycle sessions;
  private RunRegistry registry;
  private RunService runs;
  private ConversationInputRouter router;
  private final Map<String, SessionMetadata> saved = new HashMap<>();

  private SlackRuntime runtime() {
    return new SlackRuntime(
        p, store, "B", projects, sessions, registry, runs, router, Clock.systemUTC());
  }

  @BeforeEach
  void setup() {
    store = new SlackStore(root);
    p = new SlackProperties();
    p.setAllowedWorkspaceIds(Set.of("T"));
    p.setAllowedUserIds(Set.of("U"));
    p.setAllowedChannelIds(Set.of("D", "C"));
    projects = new ProjectRegistry(root.resolve("projects.json"));
    var project = projects.resolve(root);
    p.setDmProjectId(project.id());
    p.setChannelProjects(Map.of("C", project.id()));
    var repo = mock(SessionRepository.class);
    when(repo.findById(any())).thenAnswer(i -> Optional.ofNullable(saved.get(i.getArgument(0))));
    doAnswer(
            i -> {
              var m = (SessionMetadata) i.getArgument(0);
              saved.put(m.sessionId(), m);
              ((Runnable) i.getArgument(1)).run();
              return null;
            })
        .when(repo)
        .accept(any(), any());
    sessions = new SessionLifecycle(repo, Clock.systemUTC());
    registry = new RunRegistry(Clock.systemUTC());
    router = mock(ConversationInputRouter.class);
    runs =
        new RunService(
            registry,
            mock(AgentEventBus.class),
            new AgentEventFactory(Clock.systemUTC()),
            new dev.mikoto2000.rei.core.service.CommandCancellationService(),
            router::cancelQueued);
  }

  private SlackMessage m(String ts, String thread, String text) {
    return new SlackMessage("E" + ts, "T", "D", ts, thread, "U", null, null, "message", "im", text);
  }

  @Test
  void submitsThroughExistingRunQueueAndKeepsThreadSession() {
    var runtime = runtime();
    assertTrue(runtime.accept(m("1", null, "hello")));
    runtime.tick();
    var first = store.latest(m("1", null, "hello")).orElseThrow();
    verify(router).submit(any(AgentRunContext.class), eq("hello"), any());
    assertNotNull(first.run());
    assertTrue(runtime.accept(m("2", "1", "next")));
    runtime.tick();
    var second = store.latest(m("2", "1", "next")).orElseThrow();
    assertEquals(first.session(), second.session());
    assertTrue(runtime.accept(m("3", null, "separate")));
    runtime.tick();
    assertNotEquals(
        first.session(), store.latest(m("3", null, "separate")).orElseThrow().session());
  }

  @Test
  void restartRecoversAcceptedButNeverReexecutesAnAlreadyDispatchedEvent() {
    assertTrue(runtime().accept(m("1", null, "hello")));
    runtime().tick();
    runtime().tick();
    assertFalse(
        runtime()
            .accept(
                new SlackMessage(
                    "another",
                    "T",
                    "D",
                    "1",
                    null,
                    "U",
                    null,
                    null,
                    "app_mention",
                    "im",
                    "hello")));
    verify(router, times(1)).submit(any(AgentRunContext.class), anyString(), any());
  }

  @Test
  void failedSendRetriesOutboxWithoutExecutingRunAgain() {
    var runtime = runtime();
    runtime.accept(m("1", null, "hello"));
    runtime.tick();
    runtime.send(row -> SlackTransport.Delivery.retry(2));
    runtime.send(row -> SlackTransport.Delivery.sent("10"));
    verify(router, times(1)).submit(any(AgentRunContext.class), anyString(), any());
  }

  @Test
  void statusAndCancelDoNotStartAnAgentRun() {
    var runtime = runtime();
    runtime.accept(m("1", null, "hello"));
    runtime.tick();
    var receipt = store.latest(m("1", null, "hello")).orElseThrow();
    runtime.accept(m("2", "1", "rei status"));
    runtime.tick();
    verify(router, times(1)).submit(any(AgentRunContext.class), anyString(), any());
    when(router.containsRun(anyString(), anyString())).thenReturn(true);
    runtime.accept(m("3", "1", "rei cancel"));
    runtime.tick();
    assertEquals(1, registry.runIds().size());
    assertEquals("CANCEL_REQUESTED", store.byRun(receipt.run()).orElseThrow().state());
    assertTrue(store.replies().stream().anyMatch(r -> r.text().contains("キャンセルを受け付け")));
    assertTrue(store.replies().stream().noneMatch(r -> r.text().contains("キャンセル完了")));
    when(router.containsRun(anyString(), anyString())).thenReturn(false);
    runtime.tick();
    assertTrue(store.replies().stream().anyMatch(r -> r.text().contains("キャンセル完了")));
  }

  @Test
  void permissionRequiredUsesExistingApprovalAndRepliesToOwningThread() {
    var runtime = runtime();
    runtime.accept(m("1", null, "hello"));
    runtime.tick();
    var r = store.latest(m("1", null, "hello")).orElseThrow();
    runtime.onEvent(
        new AgentEvent(
            "event",
            1,
            Clock.systemUTC().instant(),
            AgentEventType.TOOL_FAILED,
            1,
            r.session(),
            null,
            r.run(),
            null,
            null,
            new ToolFailedPayload(
                "M", "write", new ErrorInformation("PermissionRequired", "unsafe raw data", null)),
            r.project()));
    assertEquals("WAITING_APPROVAL", store.byRun(r.run()).orElseThrow().state());
    assertTrue(
        store.replies().stream()
            .anyMatch(row -> row.thread().equals("1") && row.text().contains("承認待ち")));
    assertTrue(store.replies().stream().noneMatch(row -> row.text().contains("unsafe raw data")));
  }

  @Test
  void unrelatedEventNeverLeaksToSlack() {
    var runtime = runtime();
    runtime.accept(m("1", null, "hello"));
    runtime.tick();
    runtime.onEvent(
        new AgentEvent(
            "event",
            1,
            Clock.systemUTC().instant(),
            AgentEventType.MESSAGE_COMPLETED,
            1,
            "other",
            null,
            "other",
            null,
            null,
            new MessageCompletedPayload("M", "assistant", "private"),
            "other"));
    assertTrue(store.replies().stream().noneMatch(r -> r.text().contains("private")));
  }

  @Test
  void immediateFollowupToAcceptedParentIsNotLostBeforeWorkerRuns() {
    var runtime = runtime();
    assertTrue(runtime.accept(m("1", null, "hello")));
    assertTrue(runtime.accept(m("2", "1", "next")));
    runtime.tick();
    verify(router, times(2)).submit(any(AgentRunContext.class), anyString(), any());
    assertEquals(
        store.latest(m("1", null, "hello")).orElseThrow().session(),
        store.latest(m("2", "1", "next")).orElseThrow().session());
  }

  @Test
  void immediateFollowupToExplicitInvitationInExistingThreadIsAccepted() {
    var runtime = runtime();
    var invite =
        new SlackMessage(
            "invite", "T", "C", "2", "1", "U", null, null, "app_mention", null, "<@B> hello");
    assertTrue(runtime.accept(invite));
    assertTrue(
        runtime.accept(
            new SlackMessage(
                "next", "T", "C", "3", "1", "U", null, null, "message", "channel", "next")));
    runtime.tick();
    verify(router, times(2)).submit(any(AgentRunContext.class), anyString(), any());
  }

  @Test
  void unknownDispatchAfterRestartIsNeverSubmittedAgain() {
    var m = m("1", null, "hello");
    store.accept(m);
    var session = sessions.create(projects.resolveById(p.getDmProjectId()).orElseThrow(), "thread");
    var binding = store.bind(m, p.getDmProjectId(), session.sessionId());
    store.prepare(m, "interrupted", binding);
    runtime().tick();
    verifyNoInteractions(router);
    assertEquals("UNKNOWN", store.byRun("interrupted").orElseThrow().state());
  }

  @Test
  void resumedRunBecomesCurrentWithoutLosingOriginalEventMapping() {
    var runtime = runtime();
    var m = m("1", null, "hello");
    runtime.accept(m);
    runtime.tick();
    var original = store.latest(m).orElseThrow();
    store.stateRun(original.run(), "WAITING_APPROVAL");
    var context =
        new AgentRunContext(
            "resumed",
            original.session(),
            root,
            original.project(),
            AgentRunContext.RequestSource.WEB);
    registry.register(context);
    runtime.onEvent(
        new AgentEvent(
            "resumed-event",
            1,
            Clock.systemUTC().instant(),
            AgentEventType.AGENT_RUN_STARTED,
            1,
            original.session(),
            null,
            "resumed",
            null,
            null,
            null,
            original.project()));
    assertEquals("resumed", store.latest(m).orElseThrow().run());
    assertEquals(original.run(), store.byRun(original.run()).orElseThrow().run());
  }

  @Test
  void changedChannelAllowlistSuppressesPendingReplies() {
    var runtime = runtime();
    runtime.accept(m("1", null, "hello"));
    runtime.tick();
    p.setAllowedChannelIds(Set.of());
    var transport = mock(SlackTransport.class);
    runtime.send(transport);
    verifyNoInteractions(transport);
  }

  @Test
  void finalReplyUsesCompletedTurnAfterCleanupAndRecoversWithoutMessageEvents() {
    var runtime = runtime();
    var m = m("1", null, "hello");
    runtime.accept(m);
    runtime.tick();
    var receipt = store.latest(m).orElseThrow();
    assertTrue(registry.transition(receipt.run(), RunStatus.RUNNING, null));
    assertTrue(registry.transition(receipt.run(), RunStatus.COMPLETED, null));
    when(router.containsRun(anyString(), anyString())).thenReturn(true);
    runtime.onCompletedAnswer(
        (session, run) -> {
          assertEquals(receipt.session(), session);
          assertEquals(receipt.run(), run);
          return "final\nanswer";
        });
    runtime.tick();
    assertTrue(store.replies().stream().noneMatch(r -> r.text().contains("final")));
    when(router.containsRun(anyString(), anyString())).thenReturn(false);
    runtime.tick();
    assertEquals(
        1, store.replies().stream().filter(r -> r.text().contains("final\nanswer")).count());
    runtime().tick();
    assertEquals(
        1, store.replies().stream().filter(r -> r.text().contains("final\nanswer")).count());
  }

  @Test
  void capacityRejectionReportsFailureWithoutExecutingOrLeavingAnActiveReceipt() {
    doThrow(new ProjectRunQueue.CapacityExceededException())
        .when(router)
        .submit(any(AgentRunContext.class), anyString(), any());
    var runtime = runtime();
    var m = m("1", null, "hello");
    runtime.accept(m);
    runtime.tick();
    var receipt = store.latest(m).orElseThrow();
    assertEquals(RunStatus.FAILED, registry.get(receipt.run()).status());
    assertEquals("FAILED", receipt.state());
    assertTrue(store.active().isEmpty());
    assertTrue(store.replies().stream().anyMatch(r -> r.text().contains("失敗")));
    runtime.tick();
    verify(router, times(1)).submit(any(AgentRunContext.class), anyString(), any());
  }

  @Test
  void ambiguousAdmissionWithoutAQueueSlotIsFencedAndDoesNotRemainActiveForever() {
    doThrow(new IllegalStateException("unsafe diagnostics"))
        .when(router)
        .submit(any(AgentRunContext.class), anyString(), any());
    var runtime = runtime();
    var m = m("1", null, "hello");
    runtime.accept(m);
    runtime.tick();
    assertEquals("UNKNOWN", store.latest(m).orElseThrow().state());
    assertTrue(store.active().isEmpty());
    assertEquals(RunStatus.UNKNOWN, registry.get(store.latest(m).orElseThrow().run()).status());
    assertTrue(store.replies().stream().noneMatch(r -> r.text().contains("unsafe diagnostics")));
    runtime.tick();
    runtime.accept(m("2", "1", "rei status"));
    runtime.tick();
    assertTrue(store.replies().stream().anyMatch(r -> r.text().contains("状態: 結果不明")));
    verify(router, times(1)).submit(any(AgentRunContext.class), anyString(), any());
  }
}
