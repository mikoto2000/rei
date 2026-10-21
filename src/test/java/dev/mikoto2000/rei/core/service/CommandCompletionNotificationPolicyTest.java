package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** コマンド完了通知判断基準の結果と状態の契約を検証するテスト。 */
class CommandCompletionNotificationPolicyTest {

  /** 対話から使う共通操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disablesCompletionNotificationForConfiguredRootCommands() {
    CommandCompletionNotificationPolicy policy = new CommandCompletionNotificationPolicy();

    assertThat(policy.shouldNotify("chat")).isTrue();
    assertThat(policy.shouldNotify("model")).isFalse();
    assertThat(policy.shouldNotify("models")).isFalse();
    assertThat(policy.shouldNotify("project")).isFalse();
    assertThat(policy.shouldNotify("sh")).isFalse();
  }

  /** 対話から使う共通操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void notifiesWhenCommandArgumentsAreMissing() {
    CommandCompletionNotificationPolicy policy = new CommandCompletionNotificationPolicy();

    assertThat(policy.shouldNotify()).isTrue();
    assertThat(policy.shouldNotify((String) null)).isTrue();
  }
}
