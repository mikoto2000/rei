package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DotRequestRepositoryTest {
  @TempDir Path root;

  static class Time extends Clock {
    Instant now = Instant.parse("2026-10-12T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  Time clock = new Time();
  DotRequestRepository repo;

  @BeforeEach
  void start() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, clock);
  }

  Map<String, Object> publication() {
    return Map.of(
        "category",
        "TECH_RESEARCH",
        "instruction",
        "Compare options",
        "context_summary",
        "",
        "expected_output",
        "Evidence",
        "constraints",
        List.of("Read only"),
        "acceptance_criteria",
        List.of("Official evidence"));
  }

  String create() {
    return (String)
        repo.create("project", "session", null, root.toString(), publication(), Duration.ofHours(1))
            .get("request_id");
  }

  @Test
  void roundTripIsDurableAndReceiptIsNotVerification() {
    String id = create();
    assertEquals(1, repo.list(Set.of("project"), "PENDING", null, 5).size());
    var claim = repo.claim("project", id, "claim");
    assertEquals("CLAIMED", claim.get("status"));
    var result =
        Map.<String, Object>of(
            "outcome", "COMPLETED", "summary", "Found it", "content", "Untrusted report");
    var receipt = repo.submit("project", id, "claim", "submission", result, "fingerprint");
    assertEquals(true, receipt.get("persisted"));
    assertEquals("PENDING_REVIEW", receipt.get("validation_status"));
    assertEquals("NOT_REQUESTED", receipt.get("resume_status"));
    var restarted = new DotRequestRepository(root, clock);
    assertEquals(
        receipt, restarted.submit("project", id, "claim", "submission", result, "fingerprint"));
    assertEquals("RESULT_RECEIVED", restarted.get("project", id).get("status"));
    assertThrows(
        RuntimeException.class,
        () -> restarted.submit("project", id, "claim", "submission", result, "different"));
  }

  @Test
  void claimIsIdempotentExclusiveAndProjectScoped() {
    String id = create();
    var claim = repo.claim("project", id, "one");
    assertEquals(claim, repo.claim("project", id, "one"));
    assertThrows(RuntimeException.class, () -> repo.claim("project", id, "two"));
    assertThrows(RuntimeException.class, () -> repo.get("other", id));
    assertEquals(List.of(), repo.list(Set.of("other"), "PENDING", null, 5));
  }

  @Test
  void leaseExpiryIsUnknownAndDoesNotRequeue() {
    String id = create();
    repo.claim("project", id, "claim");
    clock.now = clock.now.plusSeconds(1801);
    assertEquals("UNKNOWN", repo.get("project", id).get("status"));
    assertEquals(List.of(), repo.list(Set.of("project"), "PENDING", null, 5));
    assertThrows(
        RuntimeException.class,
        () -> repo.submit("project", id, "claim", "s", Map.of("summary", "late"), "f"));
  }

  @Test
  void cancelledAndExpiredRequestsRejectResults() {
    String cancelled = create();
    repo.claim("project", cancelled, "c");
    repo.cancel("project", cancelled);
    assertThrows(
        RuntimeException.class,
        () -> repo.submit("project", cancelled, "c", "s", Map.of("summary", "late"), "f"));
    String expired = create();
    clock.now = clock.now.plusSeconds(3601);
    assertEquals("EXPIRED", repo.get("project", expired).get("status"));
    assertThrows(RuntimeException.class, () -> repo.claim("project", expired, "c"));
  }

  @Test
  void capacityAndStablePagination() {
    for (int i = 0; i < 10; i++) create();
    assertThrows(RuntimeException.class, this::create);
    var first = repo.list(Set.of("project"), "PENDING", null, 5);
    var second =
        repo.list(Set.of("project"), "PENDING", (String) first.getLast().get("request_id"), 5);
    assertEquals(5, second.size());
    assertTrue(Collections.disjoint(first, second));
    repo.claim("project", (String) first.get(0).get("request_id"), "a");
    repo.claim("project", (String) first.get(1).get("request_id"), "b");
    assertThrows(
        RuntimeException.class,
        () -> repo.claim("project", (String) first.get(2).get("request_id"), "c"));
  }
}
