package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotSnapshotRetentionTest {
  @TempDir Path root;
  DotRequestRepositoryTest.Time clock = new DotRequestRepositoryTest.Time();
  DotRequestRepository repo;
  String id;
  String claim = UUID.randomUUID().toString();
  String submission = UUID.randomUUID().toString();

  @BeforeEach
  void setup() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, clock);
    repo.limits(2, 8, Duration.ofMinutes(30), Duration.ofDays(30), Duration.ofDays(1));
    id =
        (String)
            repo.create(
                    "p",
                    null,
                    null,
                    root.toString(),
                    DotPayload.publication(
                        Map.of(
                            "instruction",
                            "private research",
                            "acceptance_criteria",
                            List.of("source"))),
                    Duration.ofHours(1))
                .get("request_id");
    repo.claim("p", id, claim);
  }

  void elapsed() {
    clock.now = clock.now.plus(Duration.ofDays(2));
    repo.maintain();
  }

  @Test
  void unknownPayloadExpiresButItsIdentityRemainsCancellable() throws Exception {
    elapsed();
    assertEquals("REQUEST_EXPIRED", assertThrows(DotException.class, () -> repo.get("p", id)).code);
    var status = repo.status("p", id);
    assertEquals("UNKNOWN", status.get("status"));
    assertEquals(true, status.get("snapshot_expired"));
    assertFalse(status.containsKey("instruction"));
    assertEquals(id, repo.open("p").getFirst().get("request_id"));
    assertFalse(repo.open("p").getFirst().containsKey("instruction"));
    assertEquals("CANCELLED", repo.cancel("p", id).get("status"));
    assertEquals("CANCELLED", repo.status("p", id).get("status"));
    assertTrue(repo.open("p").isEmpty());
  }

  @Test
  void savedReceiptReplaysEvenAfterSnapshotExpiryWithoutRevalidationOrNewResume() {
    var tools = new DotMcpTools(repo, Set.of("p"), true);
    var args =
        Map.<String, Object>of(
            "request_id",
            id,
            "claim_id",
            claim,
            "submission_id",
            submission,
            "outcome",
            "COMPLETED",
            "summary",
            "report",
            "acceptance_results",
            List.of(
                Map.of(
                    "criterion_index", 0, "status", "SATISFIED", "evidence_indices", List.of())));
    var first = tools.call("rei_request_result_submit", args);
    assertFalse(first.isError());
    elapsed();
    assertEquals(
        first.structuredContent(),
        tools.call("rei_request_result_submit", args).structuredContent());
    assertEquals(
        "REQUEST_EXPIRED",
        assertThrows(
                DotException.class, () -> repo.review("p", id, "VERIFIED", List.of(0), "checked"))
            .code);
    assertEquals("NOT_REQUESTED", repo.intent("p", id).get("status"));
  }

  @Test
  void resultNotReadyAndMissingRequestHaveDomainErrors() {
    assertEquals(
        "RESULT_NOT_READY", assertThrows(DotException.class, () -> repo.result("p", id)).code);
    assertEquals("NOT_FOUND", assertThrows(DotException.class, () -> repo.get("p", "absent")).code);
  }
}
