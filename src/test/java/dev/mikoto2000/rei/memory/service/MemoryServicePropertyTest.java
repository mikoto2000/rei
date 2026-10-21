package dev.mikoto2000.rei.memory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.model.Memory;
import dev.mikoto2000.rei.memory.model.MemoryScope;
import dev.mikoto2000.rei.memory.model.MemoryStatus;
import dev.mikoto2000.rei.memory.model.MemoryType;
import dev.mikoto2000.rei.testsupport.SqliteTestDatabase;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import javax.sql.DataSource;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.NotBlank;

/** 記憶サービスPropertyの結果と状態の契約を検証するテスト。 */
@net.jqwik.api.Tag("integration")
class MemoryServicePropertyTest {
  /** 日時や期限の条件を再現するために使う検証時刻。 */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-04T00:00:00Z"), ZoneOffset.UTC);

  // Feature: ai-memory-consolidation, Property 2: 承認記憶の保存ラウンドトリップ
  /** 長期記憶の保存と検索で、指定した条件に対する結果と状態の変化を確認する。 */
  @Property(tries = 100)
  void saveFindRoundTrip(
      @ForAll @NotBlank String content,
      @ForAll MemoryType type,
      @ForAll MemoryScope scope,
      @ForAll @DoubleRange(min = 0.0, max = 1.0) double confidence)
      throws Exception {
    try (var database = new SqliteTestDatabase()) {
      MemoryService service = newService(database.dataSource());

      Memory saved =
          service.save(
              new Memory(
                  null,
                  content,
                  type,
                  scope,
                  MemoryStatus.CANDIDATE,
                  confidence,
                  null,
                  null,
                  null));
      Memory found = service.findById(saved.id()).orElseThrow();

      assertEquals(content, found.content());
      assertEquals(type, found.type());
      assertEquals(scope, found.scope());
      assertEquals(confidence, found.confidence());
      assertEquals(MemoryStatus.ACTIVE, found.status());
    }
  }

  // Feature: ai-memory-consolidation, Property 8: 検索結果の件数上限とフィルタリング
  /** 長期記憶の保存と検索で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Property(tries = 50)
  void searchResultRespectsLimit(@ForAll @AlphaChars @NotBlank String token) throws Exception {
    try (var database = new SqliteTestDatabase()) {
      MemoryService service = newService(database.dataSource());
      for (int i = 0; i < 15; i++) {
        service.save(
            new Memory(
                null,
                token + " value-" + i,
                MemoryType.KNOWLEDGE,
                MemoryScope.SHORT_TERM,
                MemoryStatus.CANDIDATE,
                0.8d,
                null,
                null,
                null));
      }

      // AlphaChars can generate FTS operators such as OR; search for a literal token.
      var results = service.search("\"" + token + "\"", 10);
      assertTrue(results.size() <= 10);
      assertTrue(results.stream().allMatch(m -> m.status() == MemoryStatus.ACTIVE));
    }
  }

  // Feature: ai-memory-consolidation, Property 10: 論理削除の不変条件
  /** 長期記憶の保存と検索で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Property(tries = 50)
  void deletedMemoryIsNotReturnedByListOrSearch(@ForAll @AlphaChars @NotBlank String token)
      throws Exception {
    try (var database = new SqliteTestDatabase()) {
      MemoryService service = newService(database.dataSource());
      Memory saved =
          service.save(
              new Memory(
                  null,
                  token + " to-delete",
                  MemoryType.KNOWLEDGE,
                  MemoryScope.SHORT_TERM,
                  MemoryStatus.CANDIDATE,
                  0.8d,
                  null,
                  null,
                  null));
      service.updateStatus(saved.id(), MemoryStatus.DELETED);

      assertEquals(MemoryStatus.DELETED, service.findById(saved.id()).orElseThrow().status());
      assertTrue(service.listActive().stream().noneMatch(m -> m.id().equals(saved.id())));
      assertTrue(
          service.search("\"" + token + "\"", 10).stream()
              .noneMatch(m -> m.id().equals(saved.id())));
    }
  }

  // Feature: ai-memory-consolidation, Property 18: スコープ別デフォルト有効期限の設定
  /** 長期記憶の保存と検索で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Property(tries = 50)
  void defaultExpiryDependsOnScope(@ForAll MemoryScope scope) throws Exception {
    try (var database = new SqliteTestDatabase()) {
      MemoryService service = newService(database.dataSource());
      OffsetDateTime before = OffsetDateTime.now(CLOCK).truncatedTo(ChronoUnit.SECONDS);
      Memory saved =
          service.save(
              new Memory(
                  null,
                  "expiry-check",
                  MemoryType.KNOWLEDGE,
                  scope,
                  MemoryStatus.CANDIDATE,
                  0.8d,
                  null,
                  null,
                  null));
      OffsetDateTime expiresAt = saved.expiresAt();
      OffsetDateTime after = OffsetDateTime.now(CLOCK).truncatedTo(ChronoUnit.SECONDS);

      if (scope == MemoryScope.SHORT_TERM) {
        assertTrue(expiresAt != null);
        assertTrue(!expiresAt.isBefore(before.plusDays(30).minusMinutes(1)));
        assertTrue(!expiresAt.isAfter(after.plusDays(30).plusMinutes(1)));
      } else if (scope == MemoryScope.LONG_TERM) {
        assertTrue(expiresAt != null);
        assertTrue(!expiresAt.isBefore(before.plusDays(365).minusMinutes(1)));
        assertTrue(!expiresAt.isAfter(after.plusDays(365).plusMinutes(1)));
      } else if (scope == MemoryScope.PERMANENT) {
        assertNull(expiresAt);
      }
    }
  }

  // Feature: ai-memory-consolidation, Property 19: 有効期限切れ記憶の自動 deprecated 化
  /** 長期記憶の保存と検索で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Property(tries = 20)
  void expiredMemoryBecomesDeprecated(@ForAll boolean ignored) throws Exception {
    try (var database = new SqliteTestDatabase()) {
      MemoryService service = newService(database.dataSource());
      Memory expired =
          service.save(
              new Memory(
                  null,
                  "expired",
                  MemoryType.KNOWLEDGE,
                  MemoryScope.SHORT_TERM,
                  MemoryStatus.CANDIDATE,
                  0.8d,
                  OffsetDateTime.now(CLOCK).minusDays(1),
                  null,
                  null));

      var active = service.listActiveWithExpiryCheck();
      assertTrue(active.stream().noneMatch(m -> m.id().equals(expired.id())));
      assertEquals(MemoryStatus.DEPRECATED, service.findById(expired.id()).orElseThrow().status());
    }
  }

  /** 指定された条件を再現するための検証対象や入力情報を作成する。 */
  private MemoryService newService(DataSource dataSource) {
    var props =
        new MemoryProperties(
            true, 20, 80, 10, 3, 2000, 60, new MemoryProperties.ExpiryDefaults(30, 365));
    return new MemoryService(dataSource, props, CLOCK);
  }
}
