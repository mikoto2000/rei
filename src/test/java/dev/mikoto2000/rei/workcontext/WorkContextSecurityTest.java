package dev.mikoto2000.rei.workcontext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.web.ApiExceptionHandler;
import dev.mikoto2000.rei.web.ApiKeyProperties;
import dev.mikoto2000.rei.web.SecurityConfig;
import dev.mikoto2000.rei.web.WorkContextController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** 作業文脈Securityの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class WorkContextSecurityTest {
  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({SecurityConfig.class, WorkContextController.class, ApiExceptionHandler.class})
  static class Config {
    /** {@code key} の条件を再現するための補助情報を返す。 */
    @Bean
    ApiKeyProperties key() {
      return new ApiKeyProperties("test-secret");
    }

    /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
    @Bean
    WorkContextService service() {
      return mock(WorkContextService.class);
    }

    /** {@code git} の条件を再現するための補助情報を返す。 */
    @Bean
    WorkContextGit git() {
      return mock(WorkContextGit.class);
    }

    /** {@code properties} の条件を再現するための補助情報を返す。 */
    @Bean
    WorkContextProperties properties() {
      return new WorkContextProperties(false, true, 1200, 12000, 2, 20);
    }
  }

  /** 作業文脈の引き継ぎで、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void allWorkContextReadsAndWritesRequireExistingBearerAuthentication() {
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
              for (String path :
                  new String[] {
                    "/api/v1/projects/a/work-context",
                    "/api/v1/projects/a/work-context/summary",
                    "/api/v1/projects/a/work-context/history",
                    "/api/v1/projects/a/work-context/history/1"
                  }) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized());
                mvc.perform(get(path).header("Authorization", "Bearer wrong"))
                    .andExpect(status().isUnauthorized());
              }
              mvc.perform(post("/api/v1/sessions/s/work-context/update"))
                  .andExpect(status().isUnauthorized());
              // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
              verifyNoInteractions(context.getBean(WorkContextService.class));
            });
  }
}
