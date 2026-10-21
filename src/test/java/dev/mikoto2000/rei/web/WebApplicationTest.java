package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;

/** Webアプリケーションの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class WebApplicationTest {
  /** 検証条件を再現するための空の。 */
  @Configuration(proxyBeanMethods = false)
  static class Empty {
    /** {@code serverFactory} の条件を再現するための補助情報を返す。 */
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
    org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory serverFactory() {
      var factory = new org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory(0);
      factory.setAddress(java.net.InetAddress.getLoopbackAddress());
      return factory;
    }
  }

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void commandLineCannotEnableWebWithoutAnEnvironmentKey() {
    var app = new SpringApplication(Empty.class);
    WebApplication.configure(app, null);
    try (var context =
        app.run(
            "--spring.main.web-application-type=servlet",
            "--rei.web.enabled=true",
            "--logging.config=classpath:web-test-logback.xml")) {
      assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
      assertThat(context.getEnvironment().getProperty("rei.web.enabled", Boolean.class)).isFalse();
    }
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void blankEnvironmentDisablesWebBeforeContextCreation() {
    for (String key : new String[] {null, "", "   "}) {
      SpringApplication app = new SpringApplication(Empty.class);
      WebApplication.configure(app, key);
      assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
      try (var context = app.run("--rei.api-key=ignored", "--spring.main.banner-mode=off")) {
        assertThat(context.getBean(ApiKeyProperties.class).getApiKey()).isEmpty();
      }
    }
  }

  /** Web APIによる操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void environmentKeyWinsOverOtherPropertySources() {
    SpringApplication app = new SpringApplication(Empty.class);
    WebApplication.configure(app, "environment-test-key");
    assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
    try (var context =
        app.run(
            "--rei.api-key=ignored",
            "--spring.main.web-application-type=none",
            "--logging.config=classpath:web-test-logback.xml")) {
      assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
      assertThat(context.getBean(ApiKeyProperties.class).getApiKey())
          .isEqualTo("environment-test-key");
      assertThat(context.getEnvironment().getProperty("rei.api-key"))
          .isEqualTo("environment-test-key");
      assertThat(context.getBean(ApiKeyProperties.class).toString())
          .doesNotContain("environment-test-key");
    }
  }
}
