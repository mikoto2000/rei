package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class ClockToolsTest {

  @Test
  void getCurrentTimeReturnsInjectedClockTimeAndTimezone() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:42.152Z"), ZoneId.of("Asia/Tokyo"));
    ClockTools tools = new ClockTools(fixed);

    CurrentTime actual = tools.getCurrentTime();

    assertEquals("2026-08-17T01:35:42.152+09:00", actual.timestamp());
    assertEquals("Asia/Tokyo", actual.timezone());
  }
}
