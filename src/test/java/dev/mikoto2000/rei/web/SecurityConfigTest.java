package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** Security設定の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class SecurityConfigTest {
  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({SecurityConfig.class, Endpoint.class})
  static class Config {
    /** {@code apiKeyProperties} の条件を再現するための補助情報を返す。 */
    @Bean
    ApiKeyProperties apiKeyProperties() {
      return new ApiKeyProperties("test-secret");
    }
  }

  /** 検証条件を再現するための接続先。 */
  @RestController
  static class Endpoint {
    /** {@code probe} の条件を再現するための補助情報を返す。 */
    @RequestMapping("/api/v1/probe")
    String probe() {
      return "ok";
    }

    /** {@code health} の条件を再現するための補助情報を返す。 */
    @GetMapping("/actuator/health")
    String health() {
      return "ok";
    }
  }

  /** 投稿を許可することを確認する。 */
  @Test
  void bearerAuthenticationIsStatelessAndAllowsPostAndAsyncDispatch() {
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              MockMvc mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(get("/actuator/health")).andExpect(status().isOk());
              for (String header :
                  new String[] {"", "Basic test-secret", "Bearer ", "Bearer wrong"}) {
                var response =
                    mvc.perform(get("/api/v1/probe").header("Authorization", header))
                        .andExpect(status().isUnauthorized())
                        .andReturn();
                assertThat(response.getResponse().getContentAsString())
                    .doesNotContain("test-secret", "wrong");
                assertThat(response.getRequest().getSession(false)).isNull();
              }
              mvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
              for (var request :
                  java.util.List.of(
                      get("/api/v1/probe"),
                      post("/api/v1/probe"),
                      get("/api/v1/probe")
                          .with(
                              request -> {
                                request.setDispatcherType(jakarta.servlet.DispatcherType.ASYNC);
                                return request;
                              }))) {
                var result =
                    mvc.perform(request.header("Authorization", "Bearer test-secret"))
                        .andExpect(status().isOk())
                        .andReturn();
                assertThat(result.getRequest().getSession(false)).isNull();
              }
            });
  }
}
