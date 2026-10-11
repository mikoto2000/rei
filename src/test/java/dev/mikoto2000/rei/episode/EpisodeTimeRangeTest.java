package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
class EpisodeTimeRangeTest {
  @Test void calendarDateIncludesWholeDayAndExcludesFollowingDay() {
    var range=EpisodeTimeRange.of("2026-10-10","2026-10-10");
    assertTrue(range.includes(Instant.parse("2026-10-10T23:59:59.999Z")));
    assertFalse(range.includes(Instant.parse("2026-10-11T00:00:00Z")));
    assertFalse(range.includes(Instant.parse("2026-10-09T23:59:59Z")));
  }
  @Test void supportsExplicitOffsetAndRejectsInvertedRange() {
    var range=EpisodeTimeRange.of("2026-10-10T09:00:00+09:00",null);
    assertTrue(range.includes(Instant.parse("2026-10-10T00:00:00Z")));
    assertThrows(IllegalArgumentException.class,()->EpisodeTimeRange.of("2026-10-11","2026-10-09"));
  }
}
