package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.dot.DotDelegationService;
import dev.mikoto2000.rei.dot.DotResumeService;
import dev.mikoto2000.rei.websearch.WebSearchProperties;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/** 状態表示HTTP通信の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DotHttpTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(WebSearchProperties.class)
  @Import({
    WebApiIntegrationTest.Config.class,
    DotController.class,
    dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.class
  })
  static class Config {
    /** {@code dots} の条件を再現するための補助情報を返す。 */
    @Bean
    DotDelegationService dots() {
      return mock(DotDelegationService.class);
    }

    /** {@code dependencies} の条件を再現するための補助情報を返す。 */
    @Bean
    dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies() {
      return mock(dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository.class);
    }

    /** {@code checkpoints} の条件を再現するための補助情報を返す。 */
    @Bean
    dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints() {
      return mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository.class);
    }

    /** 検証で使用する判断基準を、指定された条件に合わせて用意する。 */
    @Bean
    dev.mikoto2000.rei.core.policy.ToolPermissionPolicy policy() {
      return new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
          new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(false, null, null, null));
    }

    /** {@code resumes} の条件を再現するための補助情報を返す。 */
    @Bean
    DotResumeService resumes() {
      return mock(DotResumeService.class);
    }
  }

  /** 状態表示のAPIにも認証を要求し、書き込み内容の契約を厳密に適用することを確認する。 */
  @Test
  void bearerAuthAndStrictFixedWriteDtosApplyToDotRoutes() throws Exception {
    var app = new SpringApplication(Config.class);
    WebApplication.configure(app, "integration-key");
    try (var context =
            app.run(
                "--rei.web.port=0",
                "--rei.data-dir=" + root,
                "--logging.config=classpath:web-test-logback.xml");
        var client = HttpClient.newHttpClient()) {
      int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
      String base = "/projects/project/dot-requests";
      var dots = context.getBean(DotDelegationService.class);
      var resumes = context.getBean(DotResumeService.class);
      reset(dots, resumes);
      when(dots.createHuman(eq("project"), isNull(), anyMap()))
          .thenReturn(Map.of("status", "PENDING"));
      when(resumes.verify(eq("project"), eq("id"), anyMap()))
          .thenReturn(Map.of("status", "VERIFIED"));
      assertEquals(
          401,
          ReadHttpTest.send(client, port, base, "POST", "{\"instruction\":\"research\"}", false)
              .statusCode());
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(dots, resumes);
      assertEquals(
          200,
          ReadHttpTest.send(
                  client,
                  port,
                  base,
                  "POST",
                  "{\"instruction\":\"research\",\"timeout\":3600}",
                  true)
              .statusCode());
      assertEquals(
          400,
          ReadHttpTest.send(
                  client,
                  port,
                  base,
                  "POST",
                  "{\"instruction\":\"research\",\"root\":\"forged\"}",
                  true)
              .statusCode());
      assertEquals(
          401,
          ReadHttpTest.send(
                  client,
                  port,
                  base + "/id/verify",
                  "POST",
                  "{\"confirmed\":true,\"status\":\"VERIFIED\",\"note\":\"checked\"}",
                  false)
              .statusCode());
      verifyNoInteractions(resumes);
      assertEquals(
          200,
          ReadHttpTest.send(
                  client,
                  port,
                  base + "/id/verify",
                  "POST",
                  "{\"confirmed\":true,\"status\":\"VERIFIED\",\"note\":\"checked\",\"satisfied\":[0]}",
                  true)
              .statusCode());
      verify(resumes)
          .verify(
              eq("project"),
              eq("id"),
              argThat(
                  a ->
                      Boolean.TRUE.equals(a.get("confirmed"))
                          && a.get("satisfied").equals(List.of(0))));
      assertEquals(
          401,
          ReadHttpTest.send(
                  client, port, base + "/id/resume", "POST", "{\"confirmed\":true}", false)
              .statusCode());
      verify(resumes, never()).resume(anyString(), anyString(), anyMap());
    }
  }
}
