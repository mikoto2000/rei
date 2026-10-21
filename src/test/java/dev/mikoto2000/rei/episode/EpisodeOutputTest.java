package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 経験の記録出力の結果と状態の契約を検証するテスト。 */
class EpisodeOutputTest {
  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void emptySmallTalkResponse() {
    assertTrue(new EpisodeOutput().parse("{\"episodes\":[]}", "s", "p", List.of()).isEmpty());
  }

  /** 過去の経験の活用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void refusesUnknownContinuation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new EpisodeOutput()
                .parse(
                    """
        {"episodes":[{"id":"invented","occurredAt":"2026-10-11T00:00:00Z","status":"UNVERIFIED","title":"CLI","summary":"proposal","claims":[{"kind":"reason","text":"isolation","evidence":"MODEL_PROPOSAL","runId":"r","speaker":"assistant"}],"confidence":0.8}]}
        """,
                    "s",
                    "p",
                    List.of()));
  }
}
