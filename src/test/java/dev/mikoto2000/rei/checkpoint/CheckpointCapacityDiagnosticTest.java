package dev.mikoto2000.rei.checkpoint;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 再開用の記録許容件数診断の結果と状態の契約を検証するテスト。 */
class CheckpointCapacityDiagnosticTest {
  /** 作業の中断と再開で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void storageFailureExplainsEveryReachedLimitAndRecoveryCommands() {
    var failure = CheckpointException.storageCapacity(10, 10, 1000, 25, 1024);
    assertThat(failure.code()).isEqualTo(CheckpointException.Code.CAPACITY);
    assertThat(failure.capacityDetail().settingNames())
        .isEqualTo(List.of("rei.checkpoint.max-revisions", "rei.checkpoint.max-bytes"));
    assertThat(failure.getMessage())
        .contains(
            "1000 / 1024",
            "25 バイト",
            "10 / 10",
            "rei.checkpoint.max-bytes",
            "rei.checkpoint.max-revisions",
            "/checkpoint status",
            "/checkpoint cleanup --apply",
            "application.yaml",
            "再起動",
            "/resume list",
            "/resume <taskId>",
            "承認待ち",
            "以前の履歴は保持");
  }

  /** 作業の中断と再開で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void byteLimitDoesNotSuggestIncreasingAnUnreachedRevisionLimit() {
    var failure = CheckpointException.storageCapacity(2, 10, 1000, 25, 1024);
    assertThat(failure.capacityDetail().settingNames()).containsExactly("rei.checkpoint.max-bytes");
    assertThat(failure.getMessage()).doesNotContain("rei.checkpoint.max-revisions");
  }

  /** 許容件数失敗情報について、保護された合計を伝えることを確認する。 */
  @Test
  void capacityFailureReportsProtectedTotalsAndWhyCleanupCannotMakeEnoughSpace() {
    var failure = CheckpointException.storageCapacity(10, 10, 1000, 250, 1024, 3, 900);
    assertThat(failure.capacityDetail().protectedRevisions()).isEqualTo(3);
    assertThat(failure.capacityDetail().protectedBytes()).isEqualTo(900);
    assertThat(failure.getMessage())
        .contains(
            "保護されて削除できない履歴: 3 件 / 900 バイト",
            "各タスクの最新状態と再開・承認続行に必要な履歴",
            "承認待ちでも不要な過去の履歴は整理できます",
            "保護対象の履歴と次の保存だけで上限を超える",
            "今回の保存には上限の引き上げが必要");
    assertThat(failure.getMessage()).doesNotContain("実行中・承認待ち・破損した履歴は保護");
  }

  /** 作業の中断と再開で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void allProtectedRevisionsExplainThatCleanupHasNoCandidates() {
    var failure = CheckpointException.storageCapacity(2, 10, 1000, 25, 1024, 2, 1000);
    assertThat(failure.getMessage())
        .contains("保護されて削除できない履歴: 2 件 / 1000 バイト", "保存済み履歴はすべて保護対象", "履歴整理では空きを増やせません");
  }

  /** 作業の中断と再開で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void protectedRevisionCountCanRequireIncreasingOnlyTheRevisionLimit() {
    var failure = CheckpointException.storageCapacity(3, 3, 100, 25, 1024, 3, 100);
    assertThat(failure.capacityDetail().settingNames())
        .containsExactly("rei.checkpoint.max-revisions");
    assertThat(failure.getMessage()).contains("今回の保存には上限の引き上げが必要");
  }

  /** 作業の中断と再開で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void legacyCallersDoNotMisreportMissingProtectionAccountingAsZero() {
    var failure = CheckpointException.storageCapacity(2, 10, 1000, 25, 1024);
    assertThat(failure.capacityDetail().protectedRevisions()).isEqualTo(-1);
    assertThat(failure.capacityDetail().protectedBytes()).isEqualTo(-1);
    assertThat(failure.getMessage()).contains("保護されて削除できない履歴の件数・容量は未取得");
    var detail =
        new CheckpointException.CapacityDetail(
            2, 10, 1000, 25, 1024, List.of("rei.checkpoint.max-bytes"));
    assertThat(detail.protectedRevisions()).isEqualTo(-1);
    assertThat(detail.protectedBytes()).isEqualTo(-1);
  }

  /** 作業の中断と再開で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void singleSnapshotHasItsOwnLimitAndCannotBeRepairedByPruningOtherSnapshots() {
    var failure = CheckpointException.snapshotCapacity(2048, 1024);
    assertThat(failure.capacityDetail().settingNames())
        .containsExactly("rei.checkpoint.max-snapshot-bytes");
    assertThat(failure.getMessage())
        .contains(
            "2048 / 1024", "rei.checkpoint.max-snapshot-bytes", "再起動", "全体の履歴整理では 1 件の容量は小さくなりません");
  }
}
