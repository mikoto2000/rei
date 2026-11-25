package dev.mikoto2000.rei.application.run;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.conversation.ConversationAdmissionStore;
import dev.mikoto2000.rei.conversation.SqliteSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.ChatRunAdmission;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.mcp.McpAdmission;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Only owned temporary SQLite fixtures; never performs inference. */
class ChatSubmitPinnedAdmissionTest {
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(AgentRunContext.Mode.class)
  void pinsBeforeDurablePublicationAndRegistrationAndDoesNotRepinReplay(AgentRunContext.Mode mode)
      throws Exception {
    exercise(mode, false, false);
  }

  @Test
  void mcpPinsBeforePublicationAndDoesNotRepinReplay() throws Exception {
    exercise(AgentRunContext.Mode.READ_ONLY, true, false);
  }

  @Test
  void rejectedCaptureIsNotPublishedOrReleased() throws Exception {
    exercise(AgentRunContext.Mode.EXCLUSIVE, false, true);
  }

  private void exercise(AgentRunContext.Mode mode, boolean mcp, boolean reject) throws Exception {
    new StorageMigrationCoordinator(directory).prepare();
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(Files.createDirectory(directory.resolve("project")));
    var clock = Clock.systemUTC();
    try (var admissions = new ConversationAdmissionStore(directory, clock);
        var runs = spy(new RunRegistry(clock))) {
      runs.setAdmissions(admissions);
      var lifecycle = new SessionLifecycle(new SqliteSessionRepository(directory), clock);
      lifecycle.setAdmissions(admissions);
      var acquired = new AtomicInteger();
      var released = new AtomicInteger();
      var dispatched = new AtomicInteger();
      var service = new ChatSubmitService(projects, new SessionRegistry(clock), runs, lifecycle,
          (owner, prompt) -> {
            assertEquals(1, acquired.get());
            assertNotNull(runs.get(owner.runId()));
            dispatched.incrementAndGet();
          }, true);
      service.setChatRunAdmission(new ChatRunAdmission() {
        public void accept(AgentRunContext owner, String prompt) {
          assertEquals("hello", prompt);
          assertNull(runs.get(owner.runId()), "must pin before durable admission becomes visible");
          verify(runs, never()).register(any());
          if (reject) throw new IllegalStateException("unavailable profile");
          acquired.incrementAndGet();
        }
        public void release(AgentRunContext owner) { released.incrementAndGet(); }
      });
      java.util.function.Supplier<AgentRunContext> submit = () -> mcp
          ? service.submitMcp("hello", project.id(), null,
              new McpAdmission("a".repeat(64), "b".repeat(64), Duration.ofHours(1), 0, java.util.List.of()))
          : service.submit("hello", project.id(), null, mode, "same-request");
      if (reject) {
        assertThrows(IllegalStateException.class, submit::get);
        verify(runs, never()).register(any());
        assertEquals(0, dispatched.get());
      } else {
        var first = submit.get();
        assertEquals(first, submit.get());
        assertEquals(1, acquired.get());
        assertEquals(1, dispatched.get());
      }
      assertEquals(0, released.get());
    }
  }
}
