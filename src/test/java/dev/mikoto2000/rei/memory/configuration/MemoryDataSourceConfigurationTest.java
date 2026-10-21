package dev.mikoto2000.rei.memory.configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/** 記憶データ取得元機能の構成の結果と状態の契約を検証するテスト。 */
class MemoryDataSourceConfigurationTest {

  /** 長期記憶の準備で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void memoryConsolidationDataSourceIsCreated() throws Exception {
    MemoryDataSourceConfiguration configuration = new MemoryDataSourceConfiguration();
    assertNotNull(configuration.memoryConsolidationDataSource());
  }
}
