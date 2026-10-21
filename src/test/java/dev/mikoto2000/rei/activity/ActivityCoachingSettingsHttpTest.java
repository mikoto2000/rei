package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.web.ActivityCoachingController;
import dev.mikoto2000.rei.web.ApiExceptionHandler;
import dev.mikoto2000.rei.web.ApiKeyProperties;
import dev.mikoto2000.rei.web.SecurityConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/** 作業状況助言設定HTTP通信の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ActivityCoachingSettingsHttpTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({SecurityConfig.class, ActivityCoachingController.class, ApiExceptionHandler.class})
  static class Config {
    /** {@code apiKeyProperties} の条件を再現するための補助情報を返す。 */
    @Bean
    ApiKeyProperties apiKeyProperties() {
      return new ApiKeyProperties("secret");
    }
  }

  /** {@code data} の条件を再現するための補助情報を返す。 */
  DriverManagerDataSource data() {
    return new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("coaching.db"));
  }

  /** {@code body} の条件を再現するための補助情報を返す。 */
  String body(long revision, PeriodCoaching.Settings settings) throws Exception {
    return new com.fasterxml.jackson.databind.ObjectMapper()
        .writeValueAsString(Map.of("expectedRevision", revision, "settings", settings));
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  PeriodCoachingService service(PeriodCoachingStore store, ActivityStore activity) {
    return new PeriodCoachingService(
        new ActivityTimeline(activity, Clock.systemUTC()), store, Clock.systemUTC());
  }

  /** 認証済み設定について、完全一致の改訂番号を必要とすることを確認する。 */
  @Test
  void authenticatedSettingsRequireExactRevisionAndExplicitEnableWithoutAdvice() {
    var store = new SqlitePeriodCoachingStore(data());
    var activity = org.mockito.Mockito.mock(ActivityStore.class);
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(PeriodCoachingService.class, () -> service(store, activity))
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(get("/api/v1/activity/coaching")).andExpect(status().isUnauthorized());
              mvc.perform(get("/api/v1/activity/coaching").header("Authorization", "Bearer secret"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.scope").value("LOCAL_DEVICE_COACHING_SETTINGS"))
                  .andExpect(jsonPath("$.revision").value(0))
                  .andExpect(jsonPath("$.settings.enabled").value(false));
              mvc.perform(
                      post("/api/v1/activity/coaching/settings")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content(body(0, PeriodCoaching.Settings.defaults())))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.revision").value(1))
                  .andExpect(jsonPath("$.settings.enabled").value(false));
              mvc.perform(
                      post("/api/v1/activity/coaching/enabled")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content("{\"expectedRevision\":0,\"enabled\":true}"))
                  .andExpect(status().isConflict());
              mvc.perform(
                      post("/api/v1/activity/coaching/enabled")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content("{\"expectedRevision\":1,\"enabled\":true}"))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.revision").value(2))
                  .andExpect(jsonPath("$.settings.enabled").value(true));
            });
    org.mockito.Mockito.verifyNoInteractions(activity);
    assertEquals(2, store.load().revision());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void twoInstancesCannotOverwriteSettingsFromTheSameViewedRevision() throws Exception {
    var a = new SqlitePeriodCoachingStore(data());
    var b = new SqlitePeriodCoachingStore(data());
    a.load();
    b.load();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var latch = new java.util.concurrent.CountDownLatch(1);
      var results = new ArrayList<java.util.concurrent.Future<String>>();
      for (var store : List.of(a, b))
        results.add(
            pool.submit(
                () -> {
                  latch.await();
                  try {
                    store.configureExpected(PeriodCoaching.Settings.defaults(), 0);
                    return "saved";
                  } catch (ConcurrentModificationException conflict) {
                    return "conflict";
                  }
                }));
      latch.countDown();
      assertEquals(Set.of("saved", "conflict"), Set.of(results.get(0).get(), results.get(1).get()));
    }
    assertEquals(1, a.load().revision());
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void missingFlagsInvalidCriteriaAndStaleConfigureCannotMutateEnabledSettings() {
    var store = new SqlitePeriodCoachingStore(data());
    store.setEnabled(true);
    var activity = org.mockito.Mockito.mock(ActivityStore.class);
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(PeriodCoachingService.class, () -> service(store, activity))
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              for (String invalid :
                  List.of(
                      "{}",
                      "{\"expectedRevision\":1}",
                      "{\"enabled\":false}",
                      "{\"expectedRevision\":-1,\"enabled\":false}"))
                mvc.perform(
                        post("/api/v1/activity/coaching/enabled")
                            .header("Authorization", "Bearer secret")
                            .contentType("application/json")
                            .content(invalid))
                    .andExpect(status().isBadRequest());
              for (String invalid :
                  List.of(
                      "{}",
                      body(1, PeriodCoaching.Settings.defaults().withEnabled(true)),
                      body(1, PeriodCoaching.Settings.defaults())
                          .replace("development", "unknown")))
                mvc.perform(
                        post("/api/v1/activity/coaching/settings")
                            .header("Authorization", "Bearer secret")
                            .contentType("application/json")
                            .content(invalid))
                    .andExpect(status().isBadRequest());
              mvc.perform(
                      post("/api/v1/activity/coaching/settings")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content(body(0, PeriodCoaching.Settings.defaults())))
                  .andExpect(status().isConflict());
              mvc.perform(
                      post("/api/v1/activity/coaching/enabled")
                          .contentType("application/json")
                          .content("{\"expectedRevision\":1,\"enabled\":false}"))
                  .andExpect(status().isUnauthorized());
            });
    assertTrue(store.load().settings().enabled());
    assertEquals(1, store.load().revision());
    org.mockito.Mockito.verifyNoInteractions(activity);
  }

  /** 作業状況の把握で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void savedCriteriaDisablePersistAndPreserveAdviceDeduplicationAfterRestart() {
    var store = new SqlitePeriodCoachingStore(data());
    var enabled = store.setEnabled(true);
    assertEquals("RESERVED", store.reserve(enabled, "week:fixture", "BELOW_TARGET", Instant.now()));
    var activity = org.mockito.Mockito.mock(ActivityStore.class);
    var service = service(store, activity);
    var saved =
        service.configureExpected(
            new PeriodCoaching.Settings(true, Set.of("research"), .8, 90, .2, .1, 14),
            enabled.revision());
    assertFalse(saved.settings().enabled());
    var restarted = new SqlitePeriodCoachingStore(data());
    assertEquals(saved, restarted.load());
    var current = restarted.setEnabledExpected(true, saved.revision());
    assertEquals(
        "ALREADY_SHOWN",
        restarted.reserve(
            current, "week:fixture", "BELOW_TARGET", Instant.now().plusSeconds(30 * 86400L)));
    org.mockito.Mockito.verifyNoInteractions(activity);
  }

  /** 作業状況の把握で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void disabledActivityAndOldPortsRejectUpdatesWithoutLegacyFallback() {
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
              mvc.perform(get("/api/v1/activity/coaching").header("Authorization", "Bearer secret"))
                  .andExpect(status().isServiceUnavailable());
              mvc.perform(
                      post("/api/v1/activity/coaching/enabled")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content("{\"expectedRevision\":0,\"enabled\":true}"))
                  .andExpect(status().isServiceUnavailable());
            });
    PeriodCoachingStore old =
        new PeriodCoachingStore() {
          /** 検証用に、保存された情報を読み込み、現在の処理で使える形にする。 */
          public Snapshot load() {
            return new Snapshot(0, PeriodCoaching.Settings.defaults());
          }

          /** {@code configure} の条件を再現するための補助情報を返す。 */
          public Snapshot configure(PeriodCoaching.Settings settings) {
            throw new AssertionError("legacy configure");
          }

          /** 検証用に、作業状況の把握で使用する有効状態を設定する。 */
          public Snapshot setEnabled(boolean enabled) {
            throw new AssertionError("legacy enable");
          }

          /** {@code reserve} の条件を再現するための補助情報を返す。 */
          public String reserve(Snapshot expected, String key, String reason, Instant now) {
            throw new AssertionError("reserve");
          }
        };
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(
            PeriodCoachingService.class,
            () -> service(old, org.mockito.Mockito.mock(ActivityStore.class)))
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(
                      post("/api/v1/activity/coaching/enabled")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content("{\"expectedRevision\":0,\"enabled\":true}"))
                  .andExpect(status().isServiceUnavailable());
            });
  }

  /** 作業状況の把握で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void revisionExhaustionCannotOverflowOrRestoreAStaleRevision() throws Exception {
    var store = new SqlitePeriodCoachingStore(data());
    var initial = store.load();
    try (var connection = data().getConnection();
        var statement = connection.createStatement()) {
      statement.executeUpdate(
          "UPDATE activity_period_coaching_settings SET revision=9223372036854775807");
    }
    assertThrows(
        ConcurrentModificationException.class,
        () -> store.setEnabledExpected(true, Long.MAX_VALUE));
    assertThrows(ConcurrentModificationException.class, () -> store.setEnabled(true));
    assertEquals(Long.MAX_VALUE, store.load().revision());
    assertEquals(initial.settings(), store.load().settings());
  }

  /** 作業状況の把握で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void successfulHttpCriteriaSaveDisablesAndASecondPostCannotReplayIt() {
    var store = new SqlitePeriodCoachingStore(data());
    store.setEnabled(true);
    var activity = org.mockito.Mockito.mock(ActivityStore.class);
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(PeriodCoachingService.class, () -> service(store, activity))
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              String payload =
                  body(
                      1,
                      new PeriodCoaching.Settings(false, Set.of("research"), .8, 90, .2, .1, 14));
              mvc.perform(
                      post("/api/v1/activity/coaching/settings")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content(payload))
                  .andExpect(status().isOk())
                  .andExpect(jsonPath("$.settings.enabled").value(false))
                  .andExpect(jsonPath("$.settings.targetShare").value(.8));
              mvc.perform(
                      post("/api/v1/activity/coaching/settings")
                          .header("Authorization", "Bearer secret")
                          .contentType("application/json")
                          .content(payload))
                  .andExpect(status().isConflict());
            });
    assertEquals(2, store.load().revision());
    assertFalse(store.load().settings().enabled());
    org.mockito.Mockito.verifyNoInteractions(activity);
  }
}
