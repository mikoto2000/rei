package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ChatSubmitServiceTest {
  @TempDir Path directory;

  @Test void newAndContinuedSessionsKeepProjectAndIdsWithoutWaitingForRunner() throws Exception {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var first = projects.resolve(Files.createDirectory(directory.resolve("one")));
    var second = projects.resolve(Files.createDirectory(directory.resolve("two")));
    var clock = new RunRegistryTest.MutableClock();
    var runs = new RunRegistry(clock);
    var sessions = new SessionRegistry(clock);
    List<AgentRunContext> submitted = new ArrayList<>();
    var service = new ChatSubmitService(projects, sessions, runs, (context, prompt) -> submitted.add(context));
    var created = service.submit("hello", first.id(), null);
    assertThat(submitted).containsExactly(created);
    assertThat(runs.get(created.runId()).status()).isEqualTo(RunStatus.QUEUED);
    assertThat(created.projectRoot()).isEqualTo(first.root());
    var continued = service.submit("next", first.id(), created.conversationId());
    assertThat(continued.conversationId()).isEqualTo(created.conversationId());
    assertThat(continued.runId()).isNotEqualTo(created.runId());
    assertThat(service.submit("separate", first.id(), null).conversationId()).isNotEqualTo(created.conversationId());
    assertThatThrownBy(() -> service.submit("no", second.id(), created.conversationId())).isInstanceOf(SessionConflictException.class);
    assertThatThrownBy(() -> service.submit("no", first.id(), "unknown")).isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> service.submit("no", directory.toString(), null)).isInstanceOf(ResourceNotFoundException.class);
    assertThat(projects.resolveById(directory.toString())).isEmpty();
    assertThat(projects.list()).hasSize(2);
    assertThat(submitted).hasSize(3);
  }

  @Test void sessionLifetimeIsIndependentOfRunPurgeAndRefreshesOnValidContinuation() {
    var clock = new RunRegistryTest.MutableClock();
    var sessions = new SessionRegistry(clock);
    sessions.register("session", "project");
    clock.now = clock.now.plusSeconds(1799);
    sessions.requireProject("session", "project");
    clock.now = clock.now.plusSeconds(2);
    assertThat(sessions.requireProject("session", "project")).isEqualTo("project");
    clock.now = clock.now.plusSeconds(1800);
    assertThatThrownBy(() -> sessions.requireProject("session", "project")).isInstanceOf(ResourceNotFoundException.class);
  }
}
