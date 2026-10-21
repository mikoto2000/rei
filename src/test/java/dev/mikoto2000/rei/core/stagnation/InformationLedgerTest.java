package dev.mikoto2000.rei.core.stagnation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 情報記録台帳の結果と状態の契約を検証するテスト。 */
class InformationLedgerTest {
  /** 実行の停滞への対処で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void overlapAndAdjacentRangesMergeWithoutCountingOldLines() {
    var ledger = new InformationLedger();
    assertThat(ledger.observeRange("A", "v1", 1, 401).newLines()).isEqualTo(400);
    assertThat(ledger.observeRange("A", "v1", 301, 701).newLines()).isEqualTo(300);
    assertThat(ledger.observeRange("A", "v1", 701, 10001).newLines()).isEqualTo(9300);
    assertThat(ledger.observeRange("A", "v1", 1, 10001).status())
        .isEqualTo(InformationLedger.Status.DUPLICATE);
    assertThat(ledger.snapshot().files().getFirst().ranges()).hasSize(1);
    assertThat(ledger.observeRange("B", "v1", 1, 10001).newLines()).isEqualTo(10000);
    assertThat(ledger.observeRange("A", "v2", 1, 10001).newLines()).isEqualTo(10000);
    assertThat(ledger.observeRange("A", "v1", 1, 10001).status())
        .isEqualTo(InformationLedger.Status.DUPLICATE);
  }

  /** 許容件数について、重複一覧を維持することを確認する。 */
  @Test
  void capacityKeepsDuplicatesAndReportsUnknownWithoutEviction() {
    var ledger = new InformationLedger();
    for (int i = 0; i < 1024; i++)
      assertThat(ledger.observe("key" + i)).isEqualTo(InformationLedger.Status.NEW);
    assertThat(ledger.observe("overflow")).isEqualTo(InformationLedger.Status.UNKNOWN);
    assertThat(ledger.observe("key0")).isEqualTo(InformationLedger.Status.DUPLICATE);
  }

  /** 実行の停滞への対処で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void restoreKeepsVersionedRangesAndLegacyMissingStateIsUnknown() {
    var first = new InformationLedger();
    first.observeRange("A", "v1", 1, 10001);
    var restored = new InformationLedger();
    restored.restore(first.snapshot());
    assertThat(restored.observeRange("A", "v1", 1, 10001).status())
        .isEqualTo(InformationLedger.Status.DUPLICATE);
    assertThat(restored.observeRange("A", "v2", 1, 10001).status())
        .isEqualTo(InformationLedger.Status.NEW);
    var old = new InformationLedger();
    old.restore(null);
    assertThat(old.observeRange("A", "v1", 1, 2).status())
        .isEqualTo(InformationLedger.Status.UNKNOWN);
  }

  /** 実行の停滞への対処で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void failedRestoreDoesNotErasePreviouslyKnownInformation() {
    var ledger = new InformationLedger();
    ledger.observeRange("A", "v1", 1, 10001);
    var invalid =
        new InformationLedger.State(java.util.List.of(), java.util.Set.of("x".repeat(65)), false);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ledger.restore(invalid))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(ledger.observeRange("A", "v1", 1, 10001).status())
        .isEqualTo(InformationLedger.Status.DUPLICATE);
  }

  /** 並行について、統合を読み取ることを確認する。 */
  @Test
  void concurrentReadsMergeWithoutLosingObservedRanges() throws Exception {
    var ledger = new InformationLedger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int i = 0; i < 100; i++) {
        int start = i * 100 + 1;
        futures.add(pool.submit(() -> ledger.observeRange("A", "v1", start, start + 100)));
      }
      for (var future : futures) future.get();
    }
    assertThat(ledger.snapshot().files().getFirst().ranges())
        .containsExactly(new InformationLedger.LineRange(1, 10001));
  }
}
