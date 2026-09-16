package dev.mikoto2000.rei.application.session;

import java.nio.file.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import static org.assertj.core.api.Assertions.*;

class SessionAdmissionTest {
  @TempDir Path temp;
  final Instant now = Instant.parse("2026-09-16T08:00:00Z");
  @Test void acceptedQueuedSessionCanContinueAfterRestartAndCacheExpiry() {
    var projects = new ProjectRegistry(temp.resolve("projects.json"));
    var project = projects.resolve(temp);
    var repository = new FileSessionRepository(temp.resolve("sessions.json"));
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    var runs = new RunRegistry(clock);
    var first = new ChatSubmitService(projects, new SessionRegistry(clock), runs, repository, clock, (c, p) -> {
      assertThat(repository.findById(c.conversationId())).isPresent();
      assertThat(runs.get(c.runId()).status()).isEqualTo(RunStatus.QUEUED);
    }).submit("😀".repeat(81), project.id(), null);
    var later = Clock.fixed(now.plusSeconds(3600), ZoneOffset.UTC);
    var restored = new FileSessionRepository(temp.resolve("sessions.json"));
    var submit = new ChatSubmitService(projects, new SessionRegistry(later), new RunRegistry(later), restored, later, (c,p) -> {});
    var continued = submit.submit("next", project.id(), first.conversationId());
    assertThat(continued.conversationId()).isEqualTo(first.conversationId());
    assertThat(continued.runId()).isNotEqualTo(first.runId());
    assertThat(restored.findById(first.conversationId())).contains(new SessionMetadata(first.conversationId(), project.id(), "😀".repeat(80), now, later.instant()));
    assertThatThrownBy(() -> submit.submit("next", project.id(), "unknown")).isInstanceOf(ResourceNotFoundException.class);
    var second = projects.resolve(temp.getParent());
    assertThatThrownBy(() -> submit.submit("next", second.id(), first.conversationId())).isInstanceOf(SessionConflictException.class);
    assertThat(restored.findById(first.conversationId()).orElseThrow().updatedAt()).isEqualTo(later.instant());
  }

  @Test void enqueueRejectionDoesNotLeaveSessionOrRun() {
    var projects = new ProjectRegistry(temp.resolve("projects.json"));
    var project = projects.resolve(temp);
    var repository = new FileSessionRepository(temp.resolve("sessions.json"));
    var clock = Clock.fixed(now, ZoneOffset.UTC);
    var runs = new RunRegistry(clock);
    String[] id = {null};
    var submit = new ChatSubmitService(projects, new SessionRegistry(clock), runs, repository, clock, (c,p) -> {
      id[0] = c.conversationId(); throw new RejectedExecutionException("stopped");
    });
    assertThatThrownBy(() -> submit.submit("first", project.id(), null)).isInstanceOf(RejectedExecutionException.class);
    assertThat(repository.findById(id[0])).isEmpty();
    assertThat(runs.runIds()).isEmpty();
  }
}
