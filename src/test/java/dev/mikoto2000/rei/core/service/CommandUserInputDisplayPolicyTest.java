package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CommandUserInputDisplayPolicyTest {

  @Test
  void hidesUserInputForConfiguredRootCommands() {
    CommandUserInputDisplayPolicy policy = new CommandUserInputDisplayPolicy();

    assertThat(policy.shouldDisplay("chat")).isTrue();
    assertThat(policy.shouldDisplay("feed")).isTrue();
    assertThat(policy.shouldDisplay("project")).isFalse();
    assertThat(policy.shouldDisplay("project", "list")).isFalse();
  }

  @Test
  void displaysWhenCommandArgumentsAreMissing() {
    CommandUserInputDisplayPolicy policy = new CommandUserInputDisplayPolicy();

    assertThat(policy.shouldDisplay()).isTrue();
    assertThat(policy.shouldDisplay((String) null)).isTrue();
  }
}
