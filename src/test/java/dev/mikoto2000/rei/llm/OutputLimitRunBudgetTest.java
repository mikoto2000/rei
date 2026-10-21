package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 出力上限実行予算の結果と状態の契約を検証するテスト。 */
class OutputLimitRunBudgetTest {

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void disabledCallLimitKeepsAccountingAndRestoredUsage() {
    var budget = new OutputLimitRunBudget(2, 1, null, 0, 5, false);
    budget.restore(new OutputLimitRunBudget.Snapshot(0, 120, 0, false));
    for (int i = 0; i < 200; i++) assertThat(budget.tryConsumeLlmCall()).isTrue();
    assertThat(budget.snapshot().llmCalls()).isEqualTo(320);
    assertThat(budget.remainingLlmCalls()).isEqualTo(Integer.MAX_VALUE);
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    assertThat(budget.tryConsumeStagnationReplan()).isTrue();
    assertThat(budget.tryConsumeStagnationReplan()).isFalse();
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void disabledCallLimitStillEnforcesTokensAndParentReservations() {
    var tokens = new OutputLimitRunBudget(2, 1, null, 10, 5, false);
    assertThat(tokens.tryConsumeLlmCall()).isTrue();
    tokens.recordTotalTokens(10);
    assertThat(tokens.tryConsumeLlmCall()).isFalse();
    assertThat(tokens.remainingLlmCalls()).isZero();
    var parent = new OutputLimitRunBudget(2, 1);
    var reservation =
        new OutputLimitRunBudget.LlmCallReservation() {
          /** {@code tryReserve} の条件を再現するための補助情報を返す。 */
          public boolean tryReserve() {
            return parent.tryConsumeLlmCall();
          }

          /** {@code remaining} の条件を再現するための補助情報を返す。 */
          public int remaining() {
            return parent.remainingLlmCalls();
          }
        };
    var child = new OutputLimitRunBudget(2, 1, reservation, 0, 5, false);
    assertThat(child.tryConsumeLlmCall()).isTrue();
    assertThat(child.tryConsumeLlmCall()).isFalse();
    assertThat(child.remainingLlmCalls()).isZero();
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void consumesLlmCallsUntilLimit() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 2);

    assertThat(budget.tryConsumeLlmCall()).isTrue();
    assertThat(budget.tryConsumeLlmCall()).isTrue();
    assertThat(budget.tryConsumeLlmCall()).isFalse();
    assertThat(budget.remainingLlmCalls()).isZero();
    assertThat(budget.hasRemainingLlmCalls()).isFalse();
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void consumesReplansUntilLimit() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 10);

    assertThat(budget.tryConsumeReplan()).isTrue();
    assertThat(budget.tryConsumeReplan()).isTrue();
    assertThat(budget.tryConsumeReplan()).isFalse();
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void cannotReplanWhenLlmCallsAreExhausted() {
    OutputLimitRunBudget budget = new OutputLimitRunBudget(2, 1);
    budget.tryConsumeLlmCall();

    assertThat(budget.tryConsumeReplan()).isFalse();
  }

  /** 引数補正一覧について、設定済み絶対上限を利用することを確認する。 */
  @Test
  void argumentCorrectionsUseTheirConfiguredAbsoluteLimit() {
    var budget = new OutputLimitRunBudget(2, 20, null, 0, 3);
    assertThat(budget.maxArgumentCorrections()).isEqualTo(3);
    for (int used = 0; used < 3; used++) {
      assertThat(budget.remainingArgumentCorrections()).isEqualTo(3 - used);
      assertThat(budget.tryCorrectArguments()).isTrue();
    }
    assertThat(budget.tryCorrectArguments()).isFalse();
    assertThat(budget.remainingArgumentCorrections()).isZero();
    assertThat(budget.snapshot().argumentCorrections()).isEqualTo(3);
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void legacyConstructorsUseFiveCorrectionsByDefault() {
    for (var budget :
        java.util.List.of(
            new OutputLimitRunBudget(2, 20),
            new OutputLimitRunBudget(2, 20, null),
            new OutputLimitRunBudget(2, 20, null, 0))) {
      assertThat(budget.maxArgumentCorrections()).isEqualTo(5);
      for (int used = 0; used < 5; used++) assertThat(budget.tryCorrectArguments()).isTrue();
      assertThat(budget.tryCorrectArguments()).isFalse();
    }
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void zeroDisablesCorrectionsAndNegativeLimitsAreRejected() {
    var budget = new OutputLimitRunBudget(2, 20, null, 0, 0);
    assertThat(budget.tryCorrectArguments()).isFalse();
    assertThat(budget.snapshot().argumentCorrections()).isZero();
    assertThat(budget.remainingArgumentCorrections()).isZero();
    assertThatThrownBy(() -> new OutputLimitRunBudget(2, 20, null, 0, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 用途に応じたモデル利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void restoredUsageIsKeptWhenTheConfiguredLimitIsRaisedOrLowered() {
    var raised = new OutputLimitRunBudget(2, 20, null, 0, 5);
    raised.restore(new OutputLimitRunBudget.Snapshot(0, 2, 0, false, 0, 2));
    assertThat(raised.remainingArgumentCorrections()).isEqualTo(3);
    for (int used = 0; used < 3; used++) assertThat(raised.tryCorrectArguments()).isTrue();
    assertThat(raised.tryCorrectArguments()).isFalse();

    var lowered = new OutputLimitRunBudget(2, 20, null, 0, 3);
    lowered.restore(new OutputLimitRunBudget.Snapshot(0, 2, 0, false, 0, 5));
    assertThat(lowered.remainingArgumentCorrections()).isZero();
    assertThat(lowered.tryCorrectArguments()).isFalse();
    assertThat(lowered.snapshot().argumentCorrections()).isEqualTo(5);
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void restoringOlderProgressNeverReplenishesCorrectionsAndRejectsNegativeUsage() {
    var budget = new OutputLimitRunBudget(2, 20, null, 0, 5);
    budget.restore(new OutputLimitRunBudget.Snapshot(0, 2, 0, false, 0, 4));
    budget.restore(new OutputLimitRunBudget.Snapshot(0, 1, 0, false, 0, 2));
    assertThat(budget.remainingArgumentCorrections()).isEqualTo(1);
    assertThat(budget.snapshot().argumentCorrections()).isEqualTo(4);
    assertThatThrownBy(
            () -> budget.restore(new OutputLimitRunBudget.Snapshot(0, 1, 0, false, 0, -1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(budget.snapshot().argumentCorrections()).isEqualTo(4);
  }

  /** 用途に応じたモデル利用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void oldCheckpointJsonWithoutCorrectionCountRestoresWithNoUsage() throws Exception {
    // Match the Jackson 2 mapper used by PersistentCheckpointRepository for saved snapshots.
    var snapshot =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readValue(
                "{\"replans\":0,\"llmCalls\":1,\"totalTokens\":0,\"usageUnknown\":false}",
                OutputLimitRunBudget.Snapshot.class);
    var budget = new OutputLimitRunBudget(2, 20, null, 0, 5);
    budget.restore(snapshot);
    assertThat(budget.remainingArgumentCorrections()).isEqualTo(5);
    assertThat(budget.snapshot().argumentCorrections()).isZero();
    assertThat(budget.remainingLlmCalls()).isEqualTo(19);
  }
}
