package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.dependency.DependencyObservationService;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
import dev.mikoto2000.rei.core.dependency.DependencyState;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 依存条件HTTP通信の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DependencyHttpTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @Import({WebApiIntegrationTest.Config.class, DependencyController.class})
  static class Config {
    /** {@code dependencies} の条件を再現するための補助情報を返す。 */
    @Bean
    PersistentDependencyRepository dependencies(
        @org.springframework.beans.factory.annotation.Value("${rei.data-dir}") String directory) {
      return new PersistentDependencyRepository(
          new org.springframework.jdbc.datasource.DriverManagerDataSource(
              "jdbc:sqlite:" + Path.of(directory).resolve("dependencies.db")),
          Clock.systemUTC());
    }

    /** {@code observations} の条件を再現するための補助情報を返す。 */
    @Bean
    DependencyObservationService observations() {
      return mock(DependencyObservationService.class);
    }

    /** 検証で使用する利用条件の確認を、指定された条件に合わせて用意する。 */
    @Bean
    dev.mikoto2000.rei.core.policy.ToolPermissionGuard guard() {
      return mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class);
    }

    /** {@code approvals} の条件を再現するための補助情報を返す。 */
    @Bean
    dev.mikoto2000.rei.core.policy.ToolApprovalRepository approvals() {
      return mock(dev.mikoto2000.rei.core.policy.ToolApprovalRepository.class);
    }
  }

  /** Web APIによる操作で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void authenticatedVersionedAnswerPersistsWithoutDispatchAndRejectsReplay() throws Exception {
    var app = new SpringApplication(Config.class);
    WebApplication.configure(app, "integration-key");
    String id = null;
    for (int restart = 0; restart < 2; restart++) {
      try (var context =
              app.run(
                  "--rei.web.port=0",
                  "--rei.data-dir=" + dir,
                  "--logging.config=classpath:web-test-logback.xml");
          var client = HttpClient.newHttpClient()) {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        var repo = context.getBean(PersistentDependencyRepository.class);
        if (restart == 0)
          id =
              repo.create(
                      new AgentRunContext("run", "session", dir, "p"),
                      new DependencySpec(DependencySpec.Kind.USER_ANSWER, "Choose A or B", null),
                      Duration.ofHours(1),
                      List.of())
                  .id();
        String path = "/projects/p/dependencies/" + id;
        assertEquals(401, ReadHttpTest.send(client, port, path, "GET", null, false).statusCode());
        assertEquals(
            401,
            ReadHttpTest.send(
                    client,
                    port,
                    path + "/answer",
                    "POST",
                    "{\"expectedVersion\":0,\"answer\":\"B\"}",
                    false)
                .statusCode());
        assertEquals(
            200,
            ReadHttpTest.send(client, port, "/projects/p/dependencies", "GET", null, true)
                .statusCode());
        assertEquals(
            400,
            ReadHttpTest.send(client, port, "/projects/other/dependencies/" + id, "GET", null, true)
                .statusCode());
        if (restart == 0) {
          assertEquals(
              400,
              ReadHttpTest.send(client, port, path + "/answer", "POST", "{\"answer\":\"B\"}", true)
                  .statusCode());
          assertEquals(
              409,
              ReadHttpTest.send(
                      client,
                      port,
                      path + "/answer",
                      "POST",
                      "{\"expectedVersion\":99,\"answer\":\"B\"}",
                      true)
                  .statusCode());
          var accepted =
              ReadHttpTest.send(
                  client,
                  port,
                  path + "/answer",
                  "POST",
                  "{\"expectedVersion\":0,\"answer\":\"A\"}",
                  true);
          assertEquals(200, accepted.statusCode());
          assertTrue(accepted.body().contains("\"answer\":\"A\""));
        }
        assertEquals(
            409,
            ReadHttpTest.send(
                    client,
                    port,
                    path + "/answer",
                    "POST",
                    "{\"expectedVersion\":0,\"answer\":\"B\"}",
                    true)
                .statusCode());
        assertEquals("A", repo.get("p", id).answer());
        assertEquals(DependencyState.WAITING, repo.get("p", id).state());
        assertEquals(
            200,
            ReadHttpTest.send(client, port, path + "/history", "GET", null, true).statusCode());
        verify(context.getBean(DependencyObservationService.class), times(restart == 0 ? 1 : 0))
            .flushFacts();
        verify(context.getBean(DependencyObservationService.class), never())
            .inspect(anyString(), anyString(), anyBoolean());
      }
    }
  }
}
