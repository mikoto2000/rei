package dev.mikoto2000.rei.memory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.memory.service.MemoryService;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 待機保存される予算の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SleepPersistentBudgetTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証で使用する保存と検索の窓口を、指定された条件に合わせて用意する。 */
  MemoryRepository repository() {
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("memory.db"));
    var properties = new MemoryProperties(true, 20, 80, 10, 3, 2000, 60, null);
    return new MemoryRepository(source, new MemoryService(source, properties));
  }

  /** 予約について、再開の後も対象の情報が残ることを確認する。 */
  @Test
  void reservationSurvivesRestartAndCannotBeRefundedByFailure() {
    repository().reserveSleepModelCall("p", 2, 0);
    repository().reserveSleepModelCall("p", 2, 0);
    assertTrue(
        assertThrows(RuntimeException.class, () -> repository().reserveSleepModelCall("p", 2, 0))
            .getMessage()
            .contains("LLM_CALL_BUDGET_EXCEEDED"));
    assertDoesNotThrow(() -> repository().reserveSleepModelCall("other", 2, 0));
  }

  /** 長期記憶の活用で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void tokenUsageAccumulatesAndExactBoundaryPreventsNextCall() {
    var repository = repository();
    repository.reserveSleepModelCall("p", 0, 5);
    repository.recordSleepTokens("p", 2);
    repository().reserveSleepModelCall("p", 0, 5);
    repository().recordSleepTokens("p", 3);
    assertTrue(
        assertThrows(RuntimeException.class, () -> repository().reserveSleepModelCall("p", 0, 5))
            .getMessage()
            .contains("TOKEN_BUDGET_EXCEEDED"));
  }

  /** 長期記憶の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void zeroReportedTokensRemainUnknownAcrossRestart() {
    repository().reserveSleepModelCall("p", 0, 5);
    repository().recordSleepTokens("p", 0);
    assertTrue(
        assertThrows(RuntimeException.class, () -> repository().reserveSleepModelCall("p", 0, 5))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
  }

  /** 長期記憶の活用で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void unreportedInFlightAndUnknownUsageFailClosedAcrossRestart() {
    repository().reserveSleepModelCall("pending", 0, 5);
    assertTrue(
        assertThrows(
                RuntimeException.class, () -> repository().reserveSleepModelCall("pending", 0, 5))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
    repository().reserveSleepModelCall("unknown", 0, 5);
    repository().recordSleepTokens("unknown", null);
    assertTrue(
        assertThrows(
                RuntimeException.class, () -> repository().reserveSleepModelCall("unknown", 0, 5))
            .getMessage()
            .contains("TOKEN_USAGE_UNKNOWN"));
  }

  /** 長期記憶の活用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void overshootIsChargedBeforeStoppingAndRaisingCapDoesNotEraseUsage() {
    repository().reserveSleepModelCall("p", 0, 3);
    repository().recordSleepTokens("p", 4);
    assertThrows(RuntimeException.class, () -> repository().reserveSleepModelCall("p", 0, 3));
    repository().reserveSleepModelCall("p", 0, 5);
    repository().recordSleepTokens("p", 1);
    assertThrows(RuntimeException.class, () -> repository().reserveSleepModelCall("p", 0, 5));
  }

  /** 長期記憶の活用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void concurrentReservationsCannotExceedProjectCallLimit() throws Exception {
    var repository = repository();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            () -> {
              try {
                repository.reserveSleepModelCall("p", 3, 0);
                return true;
              } catch (dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException stopped) {
                return false;
              }
            });
      int accepted = 0;
      for (var result : executor.invokeAll(tasks)) if (result.get()) accepted++;
      assertEquals(3, accepted);
    }
  }
}
