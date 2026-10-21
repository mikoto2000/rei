package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** タスク状態機能の構成の結果と状態の契約を検証するテスト。 */
class TaskStateConfigurationTest {

  /** 検証に使うタイムゾーン。 */
  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void providesTaskStateBean() {
    TaskStateConfiguration config = new TaskStateConfiguration();
    TaskState state = config.taskState(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertNotNull(state);
    assertTrue(state.isEmpty());
  }

  /** 作業状態の記録で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void beanProvidesNewInstancePerCall() {
    TaskStateConfiguration config = new TaskStateConfiguration();
    TaskState first = config.taskState(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    TaskState second = config.taskState(Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    assertTrue(first != second);
  }
}
