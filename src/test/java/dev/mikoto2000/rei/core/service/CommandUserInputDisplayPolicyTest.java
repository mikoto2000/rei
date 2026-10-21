package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** コマンド利用者入力表示判断基準の結果と状態の契約を検証するテスト。 */
class CommandUserInputDisplayPolicyTest {

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void hidesUserInputForConfiguredRootCommands() {
    CommandUserInputDisplayPolicy policy = new CommandUserInputDisplayPolicy();

    assertThat(policy.shouldDisplay("chat")).isTrue();
    assertThat(policy.shouldDisplay("feed")).isTrue();
    assertThat(policy.shouldDisplay("project")).isFalse();
    assertThat(policy.shouldDisplay("project", "list")).isFalse();
    assertThat(policy.shouldDisplay("sh")).isFalse();
  }

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void displaysWhenCommandArgumentsAreMissing() {
    CommandUserInputDisplayPolicy policy = new CommandUserInputDisplayPolicy();

    assertThat(policy.shouldDisplay()).isTrue();
    assertThat(policy.shouldDisplay((String) null)).isTrue();
  }
}
