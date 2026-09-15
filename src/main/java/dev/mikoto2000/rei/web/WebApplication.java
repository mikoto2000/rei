package dev.mikoto2000.rei.web;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.core.env.MapPropertySource;

public final class WebApplication {
  private WebApplication() {}

  public static void configure(SpringApplication application, String environmentKey) {
    String key = environmentKey == null || environmentKey.isBlank() ? "" : environmentKey;
    var type = key.isEmpty() ? WebApplicationType.NONE : WebApplicationType.SERVLET;
    application.setWebApplicationType(type);
    Map<String, Object> properties = Map.of("rei.api-key", key, "rei.web.enabled", !key.isEmpty(),
        "spring.main.web-application-type", type.name());
    // Spring binds spring.main.* after environment preparation. Pin the decision before that binding as well.
    application.addListeners((org.springframework.context.ApplicationListener<org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent>)
        event -> event.getEnvironment().getPropertySources().addFirst(new MapPropertySource("reiApiKeyEnvironment", properties)));
    application.addInitializers(context -> {
      context.getEnvironment().getPropertySources().addFirst(
          new MapPropertySource("reiApiKeyEnvironment", properties));
      context.getBeanFactory().registerSingleton("apiKeyProperties", new ApiKeyProperties(key));
    });
  }
}
