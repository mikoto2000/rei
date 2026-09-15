package dev.mikoto2000.rei.web;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

public final class WebApplication {
  private WebApplication() {}

  public static void configure(SpringApplication application, String environmentKey) {
    String key = environmentKey == null || environmentKey.isBlank() ? "" : environmentKey;
    application.setWebApplicationType(key.isEmpty() ? WebApplicationType.NONE : WebApplicationType.SERVLET);
    application.addInitializers(context -> {
      context.getEnvironment().getPropertySources().addFirst(
          new MapPropertySource("reiApiKeyEnvironment", Map.of("rei.api-key", key, "rei.web.enabled", !key.isEmpty())));
      context.getBeanFactory().registerSingleton("apiKeyProperties", new ApiKeyProperties(key));
    });
  }
}
