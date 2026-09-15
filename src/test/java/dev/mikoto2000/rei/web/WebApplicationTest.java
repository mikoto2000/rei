package dev.mikoto2000.rei.web;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Configuration;
import static org.assertj.core.api.Assertions.assertThat;

class WebApplicationTest {
  @Configuration(proxyBeanMethods = false) static class Empty {
    @org.springframework.context.annotation.Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
    org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory serverFactory() {
      var factory = new org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory(0);
      factory.setAddress(java.net.InetAddress.getLoopbackAddress());
      return factory;
    }
  }

  @Test void commandLineCannotEnableWebWithoutAnEnvironmentKey() {
    var app = new SpringApplication(Empty.class);
    WebApplication.configure(app, null);
    try (var context = app.run("--spring.main.web-application-type=servlet", "--rei.web.enabled=true",
        "--logging.config=classpath:web-test-logback.xml")) {
      assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
      assertThat(context.getEnvironment().getProperty("rei.web.enabled", Boolean.class)).isFalse();
    }
  }

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
    try (var context = app.run("--rei.api-key=ignored", "--spring.main.web-application-type=none",
        "--logging.config=classpath:web-test-logback.xml")) {
      assertThat(app.getWebApplicationType()).isEqualTo(WebApplicationType.SERVLET);
      assertThat(context.getBean(ApiKeyProperties.class).getApiKey()).isEqualTo("environment-test-key");
      assertThat(context.getEnvironment().getProperty("rei.api-key")).isEqualTo("environment-test-key");
      assertThat(context.getBean(ApiKeyProperties.class).toString()).doesNotContain("environment-test-key");
    }
  }
}
