package dev.mikoto2000.rei.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class WebApplicationTest {
  @Configuration(proxyBeanMethods = false) static class Empty {}

  @Test void blankEnvironmentDisablesWebBeforeContextCreation() {
    for (String key : new String[] {null, "", "   "}) {
      SpringApplication app = new SpringApplication(Empty.class);
      WebApplication.configure(app, key);
      assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
      try (var context = app.run("--rei.api-key=ignored", "--spring.main.banner-mode=off")) {
        assertThat(context.getBean(ApiKeyProperties.class).getApiKey()).isEmpty();
      }
    }
  }

  @Test void environmentKeyWinsOverOtherPropertySources() {
    SpringApplication app = new SpringApplication(Empty.class);
    WebApplication.configure(app, "environment-test-key");
    assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
    // The minimal context has no servlet factory; binding is independently testable.
    app.setWebApplicationType(WebApplicationType.NONE);
    try (var context = app.run("--rei.api-key=ignored", "--spring.main.banner-mode=off")) {
      assertThat(context.getBean(ApiKeyProperties.class).getApiKey()).isEqualTo("environment-test-key");
      assertThat(context.getEnvironment().getProperty("rei.api-key")).isEqualTo("environment-test-key");
      assertThat(context.getBean(ApiKeyProperties.class).toString()).doesNotContain("environment-test-key");
    }
  }
}
