package dev.mikoto2000.rei.core.contextbudget;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ContextCompressionPropertiesTest {
  @Test
  void defaultsToTwoMinutesAndAcceptsBoundaries() {
    var properties = new ContextCompressionProperties();
    assertThat(properties.getRetryCooldownSeconds()).isEqualTo(120);
    properties.validate();
    properties.setRetryCooldownSeconds(1);
    properties.validate();
    properties.setRetryCooldownSeconds(3600);
    properties.validate();
  }

  @Test
  void rejectsUnboundedOrDisabledRetryCooldowns() {
    var properties = new ContextCompressionProperties();
    for (int seconds : new int[] {-1, 0, 3601}) {
      properties.setRetryCooldownSeconds(seconds);
      assertThatThrownBy(properties::validate)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("rei.context-compression.retry-cooldown-seconds", "1", "3600");
    }
  }
}
