package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 記憶サービスの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class MemoryServiceTest {

  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path tempDir;

  /** 検証に使うサービス。 */
  private MemoryService service;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setUp() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + tempDir.resolve("memory-test.db"));
    var props =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, new MemoryProperties.ExpiryDefaults(30, 365));
    service = new MemoryService(ds, props);
  }

  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void saveAndFindByIdRoundTrip() {
    Memory saved =
        service.save(
            new Memory(
                null,
                "hello memory",
                MemoryType.KNOWLEDGE,
                MemoryScope.SHORT_TERM,
                MemoryStatus.CANDIDATE,
                0.9d,
                null,
                null,
                null));

    assertNotNull(saved.id());
    var found = service.findById(saved.id()).orElseThrow();
    assertEquals("hello memory", found.content());
    assertEquals(MemoryStatus.ACTIVE, found.status());
  }

  /** 長期記憶の保存と検索で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void savePersistsObservedAtAndValidUntil() {
    OffsetDateTime observedAt = OffsetDateTime.parse("2026-08-16T16:30:00Z");
    OffsetDateTime validUntil = OffsetDateTime.parse("2026-08-16T16:35:00Z");

    Memory saved =
        service.save(
            new Memory(
                null,
                "server is running",
                MemoryType.KNOWLEDGE,
                MemoryScope.SHORT_TERM,
                MemoryStatus.CANDIDATE,
                0.9d,
                null,
                null,
                null,
                observedAt,
                validUntil));

    Memory found = service.findById(saved.id()).orElseThrow();
    assertEquals(observedAt, found.observedAt());
    assertEquals(validUntil, found.validUntil());
  }

  /** 検索について、活動中を返すことを確認する。 */
  @Test
  void searchReturnsActiveOnly() {
    Memory saved =
        service.save(
            new Memory(
                null,
                "alpha beta gamma",
                MemoryType.KNOWLEDGE,
                MemoryScope.SHORT_TERM,
                MemoryStatus.CANDIDATE,
                0.9d,
                null,
                null,
                null));
    service.updateStatus(saved.id(), MemoryStatus.DELETED);

    List<Memory> results = service.search("alpha", 10);
    assertTrue(results.isEmpty());
  }

  /** 長期記憶の保存と検索で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void expiryCheckDeprecatesExpiredMemory() {
    Memory saved =
        service.save(
            new Memory(
                null,
                "will expire",
                MemoryType.KNOWLEDGE,
                MemoryScope.SHORT_TERM,
                MemoryStatus.CANDIDATE,
                0.9d,
                OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
                null,
                null));

    var active = service.listActiveWithExpiryCheck();
    assertTrue(active.isEmpty());
    assertEquals(MemoryStatus.DEPRECATED, service.findById(saved.id()).orElseThrow().status());
  }

  /** 長期記憶の保存と検索で、記録した情報を、定められた分類と集約の規則に従ってまとめることを確認する。 */
  @Test
  void tagsSourcesRelationsAndSummaryCanBeStored() {
    Memory saved =
        service.save(
            new Memory(
                null,
                "with meta",
                MemoryType.KNOWLEDGE,
                MemoryScope.LONG_TERM,
                MemoryStatus.CANDIDATE,
                0.8d,
                null,
                null,
                null));

    service.saveTags(saved.id(), List.of("java", "memory"));
    service.saveSource(saved.id(), "chat");
    service.saveRelation(saved.id(), saved.id(), "related");
    service.saveSummary(saved.id(), "summary text");

    assertEquals(List.of("java", "memory"), service.findTags(saved.id()));
    assertEquals(List.of("chat"), service.findSources(saved.id()));
    assertEquals(1, service.relationCount());
    assertEquals("summary text", service.findSummary(saved.id()).orElseThrow());
  }

  /** 更新状態について、無効を返すことを確認する。 */
  @Test
  void updateStatusReturnsFalseWhenIdMissing() {
    assertFalse(service.updateStatus("missing", MemoryStatus.DELETED));
  }

  /** 長期記憶の保存と検索で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void initializeSchemaThrowsIllegalStateWhenDataSourceFails() {
    DataSource badDataSource =
        new DataSource() {
          /** 検証用に、長期記憶の保存と検索で参照する接続を返す。 */
          @Override
          public java.sql.Connection getConnection() throws SQLException {
            throw new SQLException("broken");
          }

          /** 検証用に、長期記憶の保存と検索で参照する接続を返す。 */
          @Override
          public java.sql.Connection getConnection(String username, String password)
              throws SQLException {
            throw new SQLException("broken");
          }

          /** {@code unwrap} の条件を再現するための補助情報を返す。 */
          @Override
          public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("unsupported");
          }

          /** 検証用に、長期記憶の保存と検索において、Wrapper対象かどうかを判定する。 */
          @Override
          public boolean isWrapperFor(Class<?> iface) {
            return false;
          }

          /** 検証用に、長期記憶の保存と検索で参照するログ書き込み先を返す。 */
          @Override
          public java.io.PrintWriter getLogWriter() {
            return null;
          }

          /** 検証用に、長期記憶の保存と検索で使用するログ書き込み先を設定する。 */
          @Override
          public void setLogWriter(java.io.PrintWriter out) {}

          /** 検証用に、長期記憶の保存と検索で使用するログインタイムアウトを設定する。 */
          @Override
          public void setLoginTimeout(int seconds) {}

          /** 検証用に、長期記憶の保存と検索で参照するログインタイムアウトを返す。 */
          @Override
          public int getLoginTimeout() {
            return 0;
          }

          /** 検証用に、長期記憶の保存と検索で参照する親ログの出力先を返す。 */
          @Override
          public java.util.logging.Logger getParentLogger() {
            return java.util.logging.Logger.getGlobal();
          }
        };
    var props =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, new MemoryProperties.ExpiryDefaults(30, 365));

    assertThrows(IllegalStateException.class, () -> new MemoryService(badDataSource, props));
  }
}
