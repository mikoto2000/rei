package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class DotReviewTest {
  @TempDir Path root;
  DotRequestRepository repo;
  String id;
  String project = "p";

  @BeforeEach
  void setup() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, Clock.systemUTC());
    id =
        (String)
            repo.create(
                    project,
                    "s",
                    null,
                    root.toString(),
                    DotPayload.publication(
                        Map.of(
                            "instruction",
                            "research",
                            "acceptance_criteria",
                            List.of("source", "compare"))),
                    Duration.ofHours(1))
                .get("request_id");
    repo.claim(project, id, "claim");
    repo.submit(
        project,
        id,
        "claim",
        "submit",
        Map.of("summary", "report", "outcome", "COMPLETED"),
        "hash");
  }

  @Test
  void receiptRemainsPendingReviewAfterHumanVerificationAndRestart() {
    assertEquals("RESULT_RECEIVED", repo.get(project, id).get("status"));
    assertThrows(
        DotException.class,
        () -> repo.review(project, id, "VERIFIED", List.of(0), "human checked"));
    repo.review(project, id, "VERIFIED", List.of(0, 1), "human checked");
    var restarted = new DotRequestRepository(root, Clock.systemUTC());
    assertEquals("VERIFIED", restarted.get(project, id).get("status"));
    assertEquals(
        "PENDING_REVIEW",
        repo.submit(project, id, "claim", "submit", Map.of(), "hash").get("validation_status"));
  }

  @Test
  void manualIntentIsUniqueAndCrashNeverCreatesAnotherRun() {
    assertThrows(DotException.class, () -> repo.requestResume(project, id, 1));
    repo.review(project, id, "VERIFIED", List.of(0, 1), "checked");
    assertTrue(repo.requestResume(project, id, 7));
    assertFalse(repo.requestResume(project, id, 7));
    repo.attachSchedule(project, id, "timer-one");
    assertTrue(repo.startResume(project, id, "timer-one", "run-one"));
    assertFalse(repo.startResume(project, id, "timer-one", "run-two"));
    repo.recoverResumeIntents();
    assertEquals("UNKNOWN", repo.intent(project, id).get("status"));
    assertEquals("run-one", repo.intent(project, id).get("run"));
  }

  @Test
  void rejectedOrCancelledResultCannotBeAdopted() {
    repo.cancel(project, id);
    assertThrows(
        DotException.class, () -> repo.review(project, id, "VERIFIED", List.of(0, 1), "checked"));
    assertThrows(DotException.class, () -> repo.requestResume(project, id, 1));
  }
}
