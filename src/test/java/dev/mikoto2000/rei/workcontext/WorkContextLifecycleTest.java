package dev.mikoto2000.rei.workcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.WorkContextPayload;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorkContextLifecycleTest {
  @Test
  void manualExtractionFailureDisplaysCountsSettingAndActionWithoutEvidence() {
    var text = new java.io.StringWriter();
    var command = new WorkCommand(mock(WorkContextService.class), null, null, null);
    command.setShellOutput(new java.io.PrintWriter(text));
    assertEquals(
        2,
        command.execute(
            () -> {
              throw new WorkContextExtractionException(
                  WorkContextExtractionException.Reason.INPUT_TOO_LARGE, 13000, 12000);
            }));
    assertTrue(text.toString().contains("13000 tokens"));
    assertTrue(text.toString().contains("12000 tokens"));
    assertTrue(text.toString().contains("rei.work-context.max-input-tokens"));
    assertTrue(text.toString().contains("/work update"));
  }

  @Test
  void autoUpdateDefaultsOffAndEnabledFailureIsIsolatedAndOwnedByOriginalSession()
      throws Exception {
    var service = mock(WorkContextService.class);
    var bus = new InMemoryAgentEventBus();
    var context = new AgentRunContext("run", "session", Path.of("."), "project");
    try (var disabled =
        new WorkContextAutomation(
            service, new WorkContextProperties(false, true, 1200, 12000, 2, 20), bus)) {
      disabled.afterTerminal(context);
    }
    verifyNoInteractions(service);
    var ended = new CountDownLatch(1);
    var events = new CopyOnWriteArrayList<AgentEvent>();
    bus.subscribe(
        e -> {
          events.add(e);
          if (e.type() == AgentEventType.WORK_CONTEXT_UPDATE_FAILED) ended.countDown();
        });
    when(service.update("session", "run"))
        .thenThrow(
            new IllegalStateException(
                "PRIVATE_PROVIDER_BODY",
                new java.util.concurrent.TimeoutException("PRIVATE_TIMEOUT")));
    try (var enabled =
        new WorkContextAutomation(
            service, new WorkContextProperties(true, true, 1200, 12000, 2, 20), bus)) {
      enabled.afterTerminal(context);
      assertTrue(ended.await(5, TimeUnit.SECONDS));
    }
    assertEquals("project", events.getLast().projectId());
    assertEquals("session", events.getLast().sessionId());
    assertNull(events.getLast().runId());
    assertEquals("run", ((WorkContextPayload) events.getLast().payload()).sourceRunId());
    var status = ((WorkContextPayload) events.getLast().payload()).status();
    assertTrue(status.startsWith("timeout:"));
    assertTrue(status.contains("/work update"));
    assertFalse(status.contains("PRIVATE"));
    assertTrue(status.contains("rei.work-context.timeout-seconds"));
  }

  @Test
  void resumeIsShownOncePerSessionAndCanBeDisabled() {
    var service = mock(WorkContextService.class);
    when(service.current(anyString())).thenReturn(Optional.empty());
    var p = new ProjectContext(UUID.randomUUID().toString(), "project", Path.of("."));
    var seen = new HashSet<String>();
    var presenter =
        new WorkContextPresenter(
            service,
            new WorkContextProperties(false, true, 1200, 12000, 2, 20),
            mock(WorkContextGit.class));
    assertTrue(presenter.present(p, "s1", seen).orElseThrow().contains("まだありません"));
    assertTrue(presenter.present(p, "s1", seen).isEmpty());
    assertTrue(presenter.present(p, "s2", seen).isPresent());
    assertTrue(
        new WorkContextPresenter(
                service,
                new WorkContextProperties(false, false, 1200, 12000, 2, 20),
                mock(WorkContextGit.class))
            .present(p, "s3", seen)
            .isEmpty());
  }
}
