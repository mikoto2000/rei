package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 経験の記録期間の結果と状態の契約を検証するテスト。 */
class EpisodePeriodTest {
  /** 過去の経験の活用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void anUnknownEndRemainsUnknownAndAnInvertedPeriodIsRejected() {
    var claims =
        List.of(
            new Episode.Claim(
                "unknown", "result unknown", Episode.Evidence.UNVERIFIED, "r", "assistant"));
    var ongoing =
        new Episode(
            "e",
            "p",
            "s",
            "v",
            "2026-10-01T00:00:00Z",
            null,
            Episode.Status.IN_PROGRESS,
            "title",
            "summary",
            claims,
            .5);
    assertNull(ongoing.endedAt());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new Episode(
                "e",
                "p",
                "s",
                "v",
                "2026-10-01T00:00:00Z",
                "2026-09-30T00:00:00Z",
                Episode.Status.COMPLETED,
                "title",
                "summary",
                claims,
                .5));
  }
}
