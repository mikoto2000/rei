package dev.mikoto2000.rei.websearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class OpenAiWebSearchConfigurationTest {
  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(WebSearchProperties.class)
  static class Config {}

  @Test
  void environmentKeyAndProviderSettingsBindWithoutChatAuthentication() {
    new ApplicationContextRunner()
        .withUserConfiguration(Config.class)
        .withPropertyValues(
            "OPENAI_API_KEY=search-only-key",
            "rei.web-search.providers[0].name=openai",
            "rei.web-search.providers[0].model=gpt-4.1",
            "rei.web-search.providers[0].timeout-seconds=40",
            "rei.web-search.providers[0].search-context-size=low",
            "rei.web-search.providers[0].max-urls=3",
            "rei.web-search.providers[0].allowed-domains[0]=example.com")
        .run(
            context -> {
              var properties = context.getBean(WebSearchProperties.class);
              assertEquals("search-only-key", properties.getOpenaiApiKey());
              var p = properties.getProviders().getFirst();
              assertEquals("openai", p.getName());
              assertEquals("gpt-4.1", p.getModel());
              assertEquals(40, p.getTimeoutSeconds());
              assertEquals("low", p.getSearchContextSize());
              assertEquals(3, p.getMaxUrls());
              assertEquals("example.com", p.getAllowedDomains().getFirst());
            });
  }

  @Test
  void defaultProvidersNeverIncludeOpenAi() {
    assertFalse(
        new WebSearchProperties()
            .getProviders().stream().anyMatch(p -> "openai".equals(p.getName())));
  }
}
