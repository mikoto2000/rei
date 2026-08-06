package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandCompletionNotificationPolicyTest {

  @Test
  void disablesCompletionNotificationForConfiguredRootCommands() {
    CommandCompletionNotificationPolicy policy = new CommandCompletionNotificationPolicy();

    assertThat(policy.shouldNotify("chat")).isTrue();
    assertThat(policy.shouldNotify("model")).isFalse();
    assertThat(policy.shouldNotify("models")).isFalse();
    assertThat(policy.shouldNotify("project")).isFalse();
    assertThat(policy.shouldNotify("sh")).isFalse();
  }

  @Test
  void notifiesWhenCommandArgumentsAreMissing() {
    CommandCompletionNotificationPolicy policy = new CommandCompletionNotificationPolicy();

    assertThat(policy.shouldNotify()).isTrue();
    assertThat(policy.shouldNotify((String) null)).isTrue();
  }
}
