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

/** 状態表示要求保存と検索の窓口の結果と状態の契約を検証するテスト。 */
class DotRequestRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証条件を再現するための時刻。 */
  static class Time extends Clock {
    /** 日時や期限の条件を再現するために使う検証時刻。 */
    Instant now = Instant.parse("2026-10-12T00:00:00Z");

    /** 検証用に、利用者の状態表示で参照するタイムゾーンを返す。 */
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    /** 検証用に、指定されたタイムゾーンを反映した情報を作成する。 */
    public Clock withZone(ZoneId z) {
      return this;
    }

    /** {@code instant} の条件を再現するための補助情報を返す。 */
    public Instant instant() {
      return now;
    }
  }

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  Time clock = new Time();

  /** 検証に使うリポジトリ。 */
  DotRequestRepository repo;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void start() throws Exception {
    try (var gate = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(root)) {
      gate.prepare();
    }
    repo = new DotRequestRepository(root, clock);
  }

  /** {@code publication} の条件を再現するための補助情報を返す。 */
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

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  String create() {
    return (String)
        repo.create("project", "session", null, root.toString(), publication(), Duration.ofHours(1))
            .get("request_id");
  }

  /** 利用者の状態表示で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
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

  /** 利用者の状態表示で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void claimIsIdempotentExclusiveAndProjectScoped() {
    String id = create();
    var claim = repo.claim("project", id, "one");
    assertEquals(claim, repo.claim("project", id, "one"));
    assertThrows(RuntimeException.class, () -> repo.claim("project", id, "two"));
    assertThrows(RuntimeException.class, () -> repo.get("other", id));
    assertEquals(List.of(), repo.list(Set.of("other"), "PENDING", null, 5));
  }

  /** 利用者の状態表示で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
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

  /** 利用者の状態表示の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
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

  /** 利用者の状態表示で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
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
