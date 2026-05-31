package dev.mikoto2000.rei.memory.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MemoryPropertiesTest {

  @Test
  void defaultsAreAppliedWhenZeroOrNull() {
    MemoryProperties props = new MemoryProperties(true, 0, 0, 0, 0, 0, 0, null);

    assertEquals(20, props.autoTriggerMessageThreshold());
    assertEquals(80, props.autoTriggerContextPercent());
    assertEquals(10, props.searchMaxResults());
    assertEquals(3, props.searchMaxInjected());
    assertEquals(2000, props.summarizeMaxLength());
    assertEquals(60, props.conflictTimeoutSeconds());
    assertEquals(30, props.expiry().shortTermDays());
    assertEquals(365, props.expiry().longTermDays());
  }
}
