package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.web.ActivityPeriodController;
import dev.mikoto2000.rei.web.ApiExceptionHandler;
import dev.mikoto2000.rei.web.ApiKeyProperties;
import dev.mikoto2000.rei.web.SecurityConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 作業状況期間HTTP通信の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ActivityPeriodHttpTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({SecurityConfig.class, ActivityPeriodController.class, ApiExceptionHandler.class})
  static class Config {
    /** {@code apiKeyProperties} の条件を再現するための補助情報を返す。 */
    @Bean
    ApiKeyProperties apiKeyProperties() {
      return new ApiKeyProperties("secret");
    }
  }

  /** 検証で使用する保存先を、指定された条件に合わせて用意する。 */
  SqliteActivityStore store() {
    return new SqliteActivityStore(
        new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("activity.db")),
        new SessionMergePolicy(Duration.ofMinutes(3), ZoneOffset.UTC));
  }

  /** 認証済み期間について、読み取る保存済み根拠を伝えることを確認する。 */
  @Test
  void authenticatedPeriodReportReadsSavedEvidenceWithoutCapturingOrCallingAModel() {
    var store = store();
    store.append(ActivitySemanticTest.dev(0, "Terminal", "X"));
    store.append(ActivitySemanticTest.dev(1, "Terminal", "X"));
    var timeline =
        new ActivityTimeline(
            store, Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC));
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(ActivityTimeline.class, () -> timeline)
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(get("/api/v1/activity/period?period=WEEK&date=2026-09-23"))
                  .andExpect(status().isUnauthorized());
              var json =
                  mvc.perform(
                          get("/api/v1/activity/period?period=WEEK&date=2026-09-23")
                              .header("Authorization", "Bearer secret"))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.scope").value("LOCAL_DEVICE_OBSERVATIONS"))
                      .andExpect(jsonPath("$.anchorDate").value("2026-09-21"))
                      .andExpect(jsonPath("$.zone").value("Z"))
                      .andExpect(jsonPath("$.partial").value(false))
                      .andReturn()
                      .getResponse()
                      .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
              assertTrue(json.contains("観測"));
              assertFalse(json.contains("rei.log"));
              assertFalse(json.contains("evidence-0"));
              mvc.perform(
                      get("/api/v1/activity/period?period=MONTH")
                          .header("Authorization", "Bearer secret"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.anchorDate").value("2026-09-01"));
              for (String query :
                  new String[] {
                    "period=DAY", "period=WEEK&date=2026-10-07", "period=WEEK&date=not-date"
                  })
                mvc.perform(
                        get("/api/v1/activity/period?" + query)
                            .header("Authorization", "Bearer secret"))
                    .andExpect(status().isBadRequest());
            });
    assertEquals(
        2,
        store
            .findRecordsBetween(
                Instant.parse("2026-09-23T00:00:00Z"), Instant.parse("2026-09-24T00:00:00Z"))
            .size());
  }

  /** 作業状況の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void boundedSqliteQueryRejectsOverflowWithoutReturningAPartialAnalysis() {
    var store = store();
    for (int i = 0; i < 3; i++) store.append(ActivitySemanticTest.dev(i, "Terminal", "X"));
    var from = Instant.parse("2026-09-23T00:00:00Z");
    var to = from.plus(Duration.ofDays(1));
    assertEquals(store.findRecordsBetween(from, to), store.findRecordsBetweenBounded(from, to, 3));
    assertThrows(
        ActivityQueryLimitException.class, () -> store.findRecordsBetweenBounded(from, to, 2));
    assertThrows(
        IllegalArgumentException.class, () -> store.findRecordsBetweenBounded(from, to, 0));
    var toolkit =
        new ClassificationToolkit(new ActivityProperties(), null, null, Clock.systemUTC());
    assertThrows(
        ActivityQueryLimitException.class,
        () -> toolkit.wrap(store).findRecordsBetweenBounded(from, to, 2));
  }

  /** 作業状況の把握で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void unsupportedOldStoreDoesNotFallBackToAnUnboundedRead() {
    var reads = new java.util.concurrent.atomic.AtomicInteger();
    ActivityStore old =
        new ActivityStore() {
          /** {@code append} の条件を再現するため、検証用の操作や状態を扱う。 */
          public void append(ActivityRecord r) {}

          /** {@code replace} の条件を再現するため、検証用の操作や状態を扱う。 */
          public void replace(ActivityRecord r) {}

          /** 検証用に、対象を特定できるよう、期間を探す。 */
          public java.util.List<ActivitySession> findBetween(Instant a, Instant b) {
            return java.util.List.of();
          }

          /** 検証用に、対象を特定できるよう、記録一覧期間を探す。 */
          public java.util.List<ActivityRecord> findRecordsBetween(Instant a, Instant b) {
            reads.incrementAndGet();
            return java.util.List.of();
          }
        };
    var timeline =
        new ActivityTimeline(
            old, Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC));
    assertThrows(
        UnsupportedOperationException.class,
        () -> timeline.periodComparisonBounded(ActivityPeriodAnalysis.Period.WEEK, null));
    assertEquals(0, reads.get());
  }

  /** 根拠上限および無効状態機能について、明示的なエラー一覧を返すことを確認する。 */
  @Test
  void evidenceLimitAndDisabledFeatureReturnExplicitErrorsWithoutAPartialReport() {
    var store = org.mockito.Mockito.mock(ActivityStore.class);
    org.mockito.Mockito.when(
            store.findRecordsBetweenBounded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new ActivityQueryLimitException());
    var timeline =
        new ActivityTimeline(
            store, Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"), ZoneOffset.UTC));
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(ActivityTimeline.class, () -> timeline)
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(get("/api/v1/activity/period").header("Authorization", "Bearer secret"))
                  .andExpect(status().isUnprocessableEntity())
                  .andExpect(jsonPath("$.report").doesNotExist());
            });
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(get("/api/v1/activity/period").header("Authorization", "Bearer secret"))
                  .andExpect(status().isServiceUnavailable());
            });
    org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
        .append(org.mockito.ArgumentMatchers.any());
    org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
        .findRecordsBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  /** 作業状況の把握の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void oversizedStoredPayloadIsRejectedBeforeDecodingAndCancellationCannotReadStatistics() {
    var store = store();
    store.append(ActivitySemanticTest.dev(0, "Terminal", "X"));
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("activity.db"));
    org.springframework.jdbc.core.simple.JdbcClient.create(ds)
        .sql("UPDATE activity_records SET payload=?")
        .param("x".repeat(131073))
        .update();
    var from = Instant.parse("2026-09-23T00:00:00Z");
    var until = from.plus(Duration.ofDays(1));
    assertThrows(
        ActivityQueryLimitException.class, () -> store.findRecordsBetweenBounded(from, until, 10));
    try {
      Thread.currentThread().interrupt();
      assertThrows(
          IllegalStateException.class, () -> store.findRecordsBetweenBounded(from, until, 10));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }
}
