package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.web.ActivityObservationContextController;
import dev.mikoto2000.rei.web.ApiExceptionHandler;
import dev.mikoto2000.rei.web.ApiKeyProperties;
import dev.mikoto2000.rei.web.SecurityConfig;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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

/** 作業状況観測内容文脈HTTP通信の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ActivityObservationContextHttpTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証に使う日時。 */
  final Instant at = Instant.parse("2026-10-05T09:00:00Z");

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({
    SecurityConfig.class,
    ActivityObservationContextController.class,
    ApiExceptionHandler.class
  })
  static class Config {
    /** {@code apiKeyProperties} の条件を再現するための補助情報を返す。 */
    @Bean
    ApiKeyProperties apiKeyProperties() {
      return new ApiKeyProperties("secret");
    }
  }

  /** 検証用に、後から経緯を確認できるよう、指定された内容を記録する。 */
  ActivityRecord record(String project, String id, ActivityEvidence.WorkReference reference) {
    var base = ActivitySemanticTest.dev(0, "Terminal", "X");
    var evidence =
        new ActivityEvidence(
            at, base.foreground(), List.of(), "rei", project, List.of(), null, reference);
    return new ActivityRecord(
        id,
        at,
        60,
        List.of(),
        base.foreground(),
        base.inference(),
        .8,
        List.of(),
        0,
        false,
        "c",
        new ActivityRecord.Detection(
            evidence, List.of(), false, "EVIDENCE_ONLY", "READY", Map.of(), ""));
  }

  /** {@code reference} の条件を再現するための補助情報を返す。 */
  ActivityEvidence.WorkReference reference(String project) {
    return new ActivityEvidence.WorkReference(
        project,
        at,
        3,
        at.minusSeconds(20),
        new ActivityEvidence.GitReference("main", "abc", at),
        List.of(
            new ActivityEvidence.ItemReference(
                "item",
                "CURRENT_WORK",
                "OPEN",
                "EXPLICIT",
                List.of(
                    new ActivityEvidence.SourceReference(
                        "event",
                        "session",
                        "turn",
                        "run",
                        "command-ref",
                        "src/Main.java",
                        at.minusSeconds(30))))),
        false);
  }

  /** 認証済みプロジェクト観測内容について、残る再開を伝えることを確認する。 */
  @Test
  void authenticatedProjectObservationReportSurvivesRestartWithoutCurrentHistory() {
    var projects = new ProjectRegistry(dir.resolve("projects.json"));
    var project = projects.resolve(dir);
    var source = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("activity.db"));
    var merge = new SessionMergePolicy(Duration.ofSeconds(90), ZoneOffset.UTC);
    var store = new SqliteActivityStore(source, merge);
    store.append(record(project.id(), "observation", reference(project.id())));
    store.append(record("other", "private-other", reference("other")));
    var restarted = new SqliteActivityStore(source, merge);
    var service =
        new ActivityWorkContextService(
            restarted,
            p -> {
              throw new AssertionError("current history must not be consulted");
            },
            Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC));
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(ProjectRegistry.class, () -> projects)
        .withBean(ActivityWorkContextService.class, () -> service)
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              String url =
                  "/api/v1/projects/"
                      + project.id()
                      + "/activity/observation-context?date=2026-10-05";
              mvc.perform(get(url)).andExpect(status().isUnauthorized());
              String json =
                  mvc.perform(get(url).header("Authorization", "Bearer secret"))
                      .andExpect(status().isOk())
                      .andExpect(jsonPath("$.scope").value("PROJECT_OBSERVATION_CONTEXT"))
                      .andExpect(jsonPath("$.projectId").value(project.id()))
                      .andExpect(jsonPath("$.partial").value(false))
                      .andReturn()
                      .getResponse()
                      .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
              assertTrue(json.contains("src/Main.java"));
              assertTrue(json.contains("command-ref"));
              assertFalse(json.contains("private-other"));
              mvc.perform(
                      get("/api/v1/projects/unknown/activity/observation-context")
                          .header("Authorization", "Bearer secret"))
                  .andExpect(status().isNotFound());
              mvc.perform(
                      get(url.replace("2026-10-05", "2026-10-06"))
                          .header("Authorization", "Bearer secret"))
                  .andExpect(status().isBadRequest());
            });
  }

  /** 未対応の以前のについて、実施できない代替手段を保存することを確認する。 */
  @Test
  void unsupportedOldStoreCannotFallbackToUnboundedRecords() {
    var store =
        org.mockito.Mockito.mock(ActivityStore.class, org.mockito.Mockito.CALLS_REAL_METHODS);
    var service =
        new ActivityWorkContextService(
            store, p -> List.of(), Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC));
    assertThrows(
        UnsupportedOperationException.class, () -> service.observationLinksBounded("p", "today"));
    org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
        .findRecordsBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingMismatchedAndFutureSnapshotsCannotBecomeCurrentEvidence() {
    var store = org.mockito.Mockito.mock(ActivityStore.class);
    var future =
        new ActivityEvidence.WorkReference("p", at, 3, at.plusSeconds(1), null, List.of(), false);
    var partial =
        new ActivityEvidence.WorkReference(
            "p", at, 3, at.minusSeconds(20), null, reference("p").items(), true);
    org.mockito.Mockito.when(
            store.findRecordsBetweenBounded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(5000)))
        .thenReturn(
            List.of(
                record("p", "old", null),
                record("p", "future", future),
                record("p", "other", reference("other")),
                record("other", "private", reference("other")),
                record("p", "saved", partial)));
    var service =
        new ActivityWorkContextService(
            store,
            p -> {
              throw new AssertionError();
            },
            Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC));
    var links = service.observationLinksBounded("p", "today");
    assertEquals(3, links.missingContextRecords());
    assertEquals(1, links.links().size());
    assertTrue(links.partial());
    assertEquals("saved", links.links().getFirst().recordId());
  }

  /** 作業状況の把握で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void linkLimitIsExplicitAndDuplicateObservationsAreNotCountedTwice() {
    var store = org.mockito.Mockito.mock(ActivityStore.class);
    var records = new ArrayList<ActivityRecord>();
    for (int i = 0; i < 129; i++) records.add(record("p", "id-" + i, reference("p")));
    records.addFirst(records.getFirst());
    org.mockito.Mockito.when(
            store.findRecordsBetweenBounded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(records);
    var service =
        new ActivityWorkContextService(
            store, p -> List.of(), Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC));
    var result = service.observationLinksBounded("p", "today");
    assertEquals(128, result.links().size());
    assertTrue(result.partial());
    assertEquals(0, result.missingContextRecords());
  }

  /** 作業状況の把握で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void outputOverflowCannotPublishAPartialReportAndStoredItemBoundsAreEnforced() {
    var store = org.mockito.Mockito.mock(ActivityStore.class);
    var source =
        new ActivityEvidence.SourceReference(
            "e".repeat(60),
            "s".repeat(60),
            "t".repeat(60),
            "r".repeat(60),
            "c".repeat(60),
            "f".repeat(60),
            at.minusSeconds(1));
    var item =
        new ActivityEvidence.ItemReference(
            "item", "CURRENT_WORK", "OPEN", "EXPLICIT", Collections.nCopies(8, source));
    var saved =
        new ActivityEvidence.WorkReference(
            "p", at, 3, at.minusSeconds(1), null, Collections.nCopies(20, item), false);
    org.mockito.Mockito.when(
            store.findRecordsBetweenBounded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(record("p", "id", saved)));
    var service =
        new ActivityWorkContextService(
            store, p -> List.of(), Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC));
    assertThrows(
        ActivityQueryLimitException.class,
        () ->
            service.formatObservationBounded(
                service.observationLinksBounded("p", "today").links()));
    var oversized =
        new ActivityEvidence.WorkReference(
            "p", at, 3, at.minusSeconds(1), null, Collections.nCopies(21, item), false);
    org.mockito.Mockito.when(
            store.findRecordsBetweenBounded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(record("p", "id", oversized)));
    assertThrows(
        ActivityQueryLimitException.class, () -> service.observationLinksBounded("p", "today"));
  }

  /** 作業状況の把握の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledAndEmptyMidnightQueriesDoNotReadSavedEvidence() {
    var store = org.mockito.Mockito.mock(ActivityStore.class);
    var service =
        new ActivityWorkContextService(
            store,
            p -> List.of(),
            Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"), ZoneOffset.UTC));
    assertTrue(service.observationLinksBounded("p", "today").links().isEmpty());
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          java.util.concurrent.CancellationException.class,
          () -> service.observationLinksBounded("p", "today"));
    } finally {
      Thread.interrupted();
    }
    org.mockito.Mockito.verifyNoInteractions(store);
  }

  /** 大きすぎる根拠および無効状態作業状況について、なし部分的な結果HTTP通信結果を返すことを確認する。 */
  @Test
  void oversizedEvidenceAndDisabledActivityReturnNoPartialHttpResult() {
    var projects = new ProjectRegistry(dir.resolve("projects.json"));
    var project = projects.resolve(dir);
    var store = org.mockito.Mockito.mock(ActivityStore.class);
    org.mockito.Mockito.when(
            store.findRecordsBetweenBounded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenThrow(new ActivityQueryLimitException());
    var service =
        new ActivityWorkContextService(
            store, p -> List.of(), Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC));
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(ProjectRegistry.class, () -> projects)
        .withBean(ActivityWorkContextService.class, () -> service)
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
                      get("/api/v1/projects/" + project.id() + "/activity/observation-context")
                          .header("Authorization", "Bearer secret"))
                  .andExpect(status().isUnprocessableEntity())
                  .andExpect(jsonPath("$.report").doesNotExist());
            });
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(ProjectRegistry.class, () -> projects)
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
                      get("/api/v1/projects/" + project.id() + "/activity/observation-context")
                          .header("Authorization", "Bearer secret"))
                  .andExpect(status().isServiceUnavailable());
            });
  }

  /** 作業状況の把握で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void longDisplayReferencesAreMarkedIncompleteAndFutureToolSourcesAreMissing() {
    var store = org.mockito.Mockito.mock(ActivityStore.class);
    var future =
        new ActivityEvidence.SourceReference("event", "s", "t", "r", "c", null, at.plusSeconds(1));
    var item =
        new ActivityEvidence.ItemReference(
            "i".repeat(241), "CURRENT_WORK", "OPEN", "EXPLICIT", List.of());
    var saved =
        new ActivityEvidence.WorkReference(
            "p", at, 3, at.minusSeconds(1), null, List.of(item), false);
    var invalid =
        new ActivityEvidence.WorkReference(
            "p",
            at,
            3,
            at.minusSeconds(1),
            null,
            List.of(
                new ActivityEvidence.ItemReference(
                    "item", "CURRENT_WORK", "OPEN", "EXPLICIT", List.of(future))),
            false);
    org.mockito.Mockito.when(
            store.findRecordsBetweenBounded(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
        .thenReturn(List.of(record("p", "saved", saved), record("p", "future", invalid)));
    var result =
        new ActivityWorkContextService(
                store, p -> List.of(), Clock.fixed(at.plusSeconds(60), ZoneOffset.UTC))
            .observationLinksBounded("p", "today");
    assertTrue(result.partial());
    assertEquals(1, result.links().size());
    assertEquals(1, result.missingContextRecords());
  }
}
