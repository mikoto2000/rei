package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.mikoto2000.rei.application.cli.ActivityRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 作業状況要求の結果と状態の契約を検証するテスト。 */
class ActivityRequestTest {
  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void activityUsesPurePicocliGrammarForParentOptionsAndCoachingDefaults() {
    var summary = ActivityRequest.parse(List.of("--verbose", "summary", "yesterday"));
    assertEquals("summary", summary.operation());
    assertEquals("yesterday", summary.date());
    var configure = ActivityRequest.parse(List.of("coaching", "configure"));
    assertEquals(.6, configure.settings().targetShare());
    assertEquals(120, configure.settings().minObservedMinutes());
    assertEquals(7, configure.settings().cooldownDays());
    var unknowns = ActivityRequest.parse(List.of("classification", "unknowns", "--top=8"));
    assertEquals(8, unknowns.top());
    assertThrows(
        IllegalArgumentException.class,
        () -> ActivityRequest.parse(List.of("coaching", "on", "--target-share", "0.6")));
    assertThrows(
        IllegalArgumentException.class,
        () -> ActivityRequest.parse(List.of("coaching", "on", "2026-10-11")));
  }
}
