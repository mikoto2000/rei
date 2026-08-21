package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class TaskStateConfigurationTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void providesTaskStateBean() {
    TaskStateConfiguration config = new TaskStateConfiguration();
    TaskState state = config.taskState(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertNotNull(state);
    assertTrue(state.isEmpty());
  }

  @Test
  void beanProvidesNewInstancePerCall() {
    TaskStateConfiguration config = new TaskStateConfiguration();
    TaskState first = config.taskState(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    TaskState second = config.taskState(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertTrue(first != second);
  }
}
