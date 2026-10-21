package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 経験の記録時刻対象範囲の結果と状態の契約を検証するテスト。 */
class EpisodeTimeRangeTest {
  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void calendarDateIncludesWholeDayAndExcludesFollowingDay() {
    var range = EpisodeTimeRange.of("2026-10-10", "2026-10-10");
    assertTrue(range.includes(Instant.parse("2026-10-10T23:59:59.999Z")));
    assertFalse(range.includes(Instant.parse("2026-10-11T00:00:00Z")));
    assertFalse(range.includes(Instant.parse("2026-10-09T23:59:59Z")));
  }

  /** 過去の経験の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void supportsExplicitOffsetAndRejectsInvertedRange() {
    var range = EpisodeTimeRange.of("2026-10-10T09:00:00+09:00", null);
    assertTrue(range.includes(Instant.parse("2026-10-10T00:00:00Z")));
    assertThrows(
        IllegalArgumentException.class, () -> EpisodeTimeRange.of("2026-10-11", "2026-10-09"));
  }
}
