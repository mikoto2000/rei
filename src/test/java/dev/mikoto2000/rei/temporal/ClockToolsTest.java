package dev.mikoto2000.rei.temporal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** 時刻の取得元ツール一覧の結果と状態の契約を検証するテスト。 */
class ClockToolsTest {

  /** 取得現在時刻について、追加された時刻の取得元時刻を返すことを確認する。 */
  @Test
  void getCurrentTimeReturnsInjectedClockTimeAndTimezone() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:42.152Z"), ZoneId.of("Asia/Tokyo"));
    ClockTools tools = new ClockTools(fixed);

    CurrentTime actual = tools.getCurrentTime();

    assertEquals("2026-08-17T01:35:42.152+09:00", actual.timestamp());
    assertEquals("Asia/Tokyo", actual.timezone());
  }
}
