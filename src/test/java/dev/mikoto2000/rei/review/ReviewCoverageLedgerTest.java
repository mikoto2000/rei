package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** レビュー対象範囲記録台帳の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ReviewCoverageLedgerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う記録台帳。 */
  ReviewCoverageLedger ledger;

  /** 検証に使う対象範囲。 */
  final ReviewCoverageLedger.Scope scope = new ReviewCoverageLedger.Scope("project", "chat", "run");

  /** 検証に使う最初。 */
  final ReviewCoverageLedger.Span first = new ReviewCoverageLedger.Span(0, 3, 1, 1, 0, 3);

  /** 検証に使う秒。 */
  final ReviewCoverageLedger.Span second = new ReviewCoverageLedger.Span(3, 6, 1, 1, 3, 6);

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() throws Exception {
    try (var migration = new StorageMigrationCoordinator(dir)) {
      migration.prepare();
    }
    ledger = new ReviewCoverageLedger(new StorageDatabase(dir));
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  String create() {
    return ledger
        .create(
            scope,
            "technical correctness",
            "v1",
            List.of(
                new ReviewCoverageLedger.Target(
                    "a.md", "sha1", 1, List.of(new ReviewCoverageLedger.Span(0, 6, 1, 1, 0, 6)))))
        .id();
  }

  /** {@code range} の条件を再現するための補助情報を返す。 */
  ReviewCoverageLedger.Range range(ReviewCoverageLedger.Span span) {
    return new ReviewCoverageLedger.Range("a.md", "sha1", span, "hash" + span.start());
  }

  /** 対象範囲を保持することを確認する。 */
  @Test
  void fetchedDeliveredReviewedAreDistinctAndResumeRetainsCoverage() {
    String id = create();
    ledger.fetched(id, scope, range(first));
    assertThatThrownBy(() -> ledger.reviewed(id, scope, range(first), "checked", List.of()))
        .hasMessageContaining("REVIEW_RESULT_INVALID");
    ledger.delivered(id, scope, List.of(range(first)));
    ledger.reviewed(id, scope, range(first), "checked", List.of());
    assertThat(ledger.get(id, scope).status()).isEqualTo("REVIEW_PARTIAL");
    ledger = new ReviewCoverageLedger(new StorageDatabase(dir));
    assertThat(ledger.get(id, scope).ranges()).hasSize(1);
    ledger.fetched(id, scope, range(second));
    ledger.delivered(id, scope, List.of(range(second)));
    ledger.reviewed(id, scope, range(second), "checked", List.of());
    assertThat(ledger.get(id, scope).status()).isEqualTo("COMPLETE");
  }

  /** 変更内容のレビューで、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void duplicatesDoNotAdvanceRevisionAndOtherConversationIsDenied() {
    String id = create();
    ledger.fetched(id, scope, range(first));
    long revision = ledger.get(id, scope).revision();
    ledger.fetched(id, scope, range(first));
    assertThat(ledger.get(id, scope).revision()).isEqualTo(revision);
    assertThatThrownBy(
            () -> ledger.get(id, new ReviewCoverageLedger.Scope("project", "other", "run")))
        .hasMessageContaining("ACCESS_DENIED");
  }

  /** 変更内容のレビューで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void explicitlyGrantedChildCanListTheSameJobWithoutCrossProjectAccess() {
    String id = create();
    var child = new ReviewCoverageLedger.Scope("project", "subagent:child", "child");
    ledger.grant(id, scope, child);
    assertThat(ledger.jobs(child)).containsExactly(id);
    assertThatThrownBy(
            () -> ledger.grant(id, scope, new ReviewCoverageLedger.Scope("other", "chat", "other")))
        .hasMessageContaining("ACCESS_DENIED");
  }

  /** 再開用の記録再開について、完全一致の対象範囲一覧を保持することを確認する。 */
  @Test
  void checkpointResumeRetainsExactRangesAndFindings() {
    String id = create();
    ledger.fetched(id, scope, range(first));
    ledger.delivered(id, scope, List.of(range(first)));
    var resumed = new ReviewCoverageLedger.Scope("project", "chat", "resumed");
    ledger.resume(id, scope, resumed);
    assertThat(ledger.get(id, resumed).ranges()).isEqualTo(ledger.get(id, scope).ranges());
    assertThat(ledger.jobs(resumed)).containsExactly(id);
  }

  /** 変更内容のレビューで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void staleVersionNeverCompletesAndExternalSuccessIsUnverified() {
    String id = create();
    ledger.externalReport(id, scope, "success");
    assertThat(ledger.get(id, scope).status()).isEqualTo("REVIEW_UNVERIFIED");
    ledger.fetched(id, scope, range(first));
    ledger.delivered(id, scope, List.of(range(first)));
    ledger.reviewed(id, scope, range(first), "checked", List.of());
    ledger.invalidate(id, scope, "a.md", "sha2");
    assertThat(ledger.get(id, scope).status()).isEqualTo("STALE_VERSION");
    assertThatThrownBy(() -> ledger.fetched(id, scope, range(second)))
        .hasMessageContaining("STALE_VERSION");
  }
}
