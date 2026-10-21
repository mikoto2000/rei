package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import org.junit.jupiter.api.Test;

/** 共有実行トークン数の上限の結果と状態の契約を検証するテスト。 */
class SharedRunTokenLimitTest {
  /** 並行伝えるについて、各対象報告された内容トークンを保持することを確認する。 */
  @Test
  void concurrentReportsPreserveEveryReportedToken() throws Exception {
    var budget = new OutputLimitRunBudget(0, 10, null, 80000);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
      for (int worker = 0; worker < 8; worker++) {
        tasks.add(
            executor.submit(
                () -> {
                  for (int i = 0; i < 10000; i++) budget.recordTotalTokens(1);
                }));
      }
      for (var task : tasks) task.get();
    }
    assertThat(budget.totalTokens()).isEqualTo(80000);
    assertThat(budget.tryConsumeLlmCall()).isFalse();
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void parentAndChildrenShareReportedTokensAndStopBeforeNextCall() {
    var budget = new OutputLimitRunBudget(0, 10, null, 10);
    var run = new RunExecutionContext("run", budget, null, null, null);
    run.recordTotalTokens(6);
    var child = run.sharedLlmReservation();
    assertThat(child.tryReserve()).isTrue();
    child.recordTotalTokens(4);
    assertThat(child.tryReserve()).isFalse();
    assertThatThrownBy(run::consumeNextLlmCall)
        .isInstanceOf(ExecutionStoppedException.class)
        .hasMessage("TOKEN_BUDGET_EXCEEDED");
    assertThat(budget.totalTokens()).isEqualTo(10);
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void unknownUsageAndOvershootFailClosedOnlyWhenEnabled() {
    for (Integer usage : new Integer[] {null, 0, -1, 11}) {
      var run =
          new RunExecutionContext(
              "run", new OutputLimitRunBudget(0, 10, null, 10), null, null, null);
      assertThatThrownBy(() -> run.recordTotalTokens(usage))
          .isInstanceOf(ExecutionStoppedException.class);
      assertThat(run.sharedLlmReservation().tryReserve()).isFalse();
    }
    var old = new OutputLimitRunBudget(0, 10);
    assertThat(old.tokenLimitEnabled()).isFalse();
    assertThatCode(() -> old.recordTotalTokens(null)).doesNotThrowAnyException();
  }
}
