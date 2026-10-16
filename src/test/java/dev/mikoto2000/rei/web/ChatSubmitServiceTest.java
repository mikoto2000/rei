package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.application.run.SessionConflictException;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class ChatSubmitServiceTest {
  @TempDir Path directory;

  @Test
  void captureIsConsumedBeforeDispatchOnlyByItsClientAndSession() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(Files.createDirectory(directory.resolve("project")));
    var clock = new RunRegistryTest.MutableClock();
    var lifecycle =
        new dev.mikoto2000.rei.application.session.SessionLifecycle(
            new dev.mikoto2000.rei.conversation.FileSessionRepository(
                directory.resolve("sessions.json")),
            clock);
    var session = lifecycle.create(project, "capture");
    var captures = new dev.mikoto2000.rei.llm.capture.CaptureStore();
    String client = UUID.randomUUID().toString();
    captures.reserve("cli:" + client, session.sessionId());
    var service =
        new ChatSubmitService(
            projects,
            new SessionRegistry(clock),
            new RunRegistry(clock),
            lifecycle,
            (context, message) ->
                assertThat(captures.logicalCall(context.runId(), null)).isNotNull());
    service.setCaptures(captures);
    var context =
        service.submit(
            "hello",
            project.id(),
            session.sessionId(),
            AgentRunContext.Mode.EXCLUSIVE,
            null,
            client);
    assertThat(captures.sessions()).hasSize(1);
    assertThat(captures.sessions().getFirst().runId()).isEqualTo(context.runId());
    assertThatThrownBy(
            () ->
                service.submit(
                    "hello",
                    project.id(),
                    session.sessionId(),
                    AgentRunContext.Mode.EXCLUSIVE,
                    null,
                    "bad"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void newAndContinuedSessionsKeepProjectAndIdsWithoutWaitingForRunner() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var first = projects.resolve(Files.createDirectory(directory.resolve("one")));
    var second = projects.resolve(Files.createDirectory(directory.resolve("two")));
    var clock = new RunRegistryTest.MutableClock();
    var runs = new RunRegistry(clock);
    var sessions = new SessionRegistry(clock);
    List<AgentRunContext> submitted = new ArrayList<>();
    var service =
        new ChatSubmitService(
            projects,
            sessions,
            runs,
            new dev.mikoto2000.rei.conversation.FileSessionRepository(
                directory.resolve("sessions.json")),
            clock,
            (context, prompt) -> submitted.add(context));
    var created = service.submit("hello", first.id(), null);
    assertThat(submitted).containsExactly(created);
    assertThat(runs.get(created.runId()).status()).isEqualTo(RunStatus.QUEUED);
    assertThat(created.projectRoot()).isEqualTo(first.root());
    var continued = service.submit("next", first.id(), created.conversationId());
    assertThat(continued.conversationId()).isEqualTo(created.conversationId());
    assertThat(continued.runId()).isNotEqualTo(created.runId());
    assertThat(service.submit("separate", first.id(), null).conversationId())
        .isNotEqualTo(created.conversationId());
    assertThatThrownBy(() -> service.submit("no", second.id(), created.conversationId()))
        .isInstanceOf(SessionConflictException.class);
    assertThatThrownBy(() -> service.submit("no", first.id(), "unknown"))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.submit("no", directory.toString(), null))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThat(projects.resolveById(directory.toString())).isEmpty();
    assertThat(projects.list()).hasSize(2);
    assertThat(submitted).hasSize(3);
  }

  @Test
  void sessionLifetimeIsIndependentOfRunPurgeAndRefreshesOnValidContinuation() {
    var clock = new RunRegistryTest.MutableClock();
    var sessions = new SessionRegistry(clock);
    sessions.register("session", "project");
    clock.now = clock.now.plusSeconds(1799);
    sessions.requireProject("session", "project");
    clock.now = clock.now.plusSeconds(2);
    assertThat(sessions.requireProject("session", "project")).isEqualTo("project");
    clock.now = clock.now.plusSeconds(1800);
    assertThatThrownBy(() -> sessions.requireProject("session", "project"))
        .isInstanceOf(ResourceNotFoundException.class);
  }
}
