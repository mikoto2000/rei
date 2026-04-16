package dev.mikoto2000.rei.websearch;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class WebSearchPropertiesLogger implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(WebSearchPropertiesLogger.class);

  private final WebSearchProperties properties;

  public WebSearchPropertiesLogger(WebSearchProperties properties) {
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    List<WebSearchProperties.ProviderProperties> providers = properties.getProviders();
    List<String> providerSummaries = providers == null
        ? List.of()
        : providers.stream()
            .filter(provider -> provider != null)
            .map(provider -> "%s(baseUrl=%s, apiKeyConfigured=%s)".formatted(
                provider.getName(),
                provider.getBaseUrl(),
                provider.getApiKey() != null && !provider.getApiKey().isBlank()))
            .toList();

    log.info(
        "Web search config: enabled={}, timeoutSeconds={}, maxResults={}, providersCount={}, providers={}",
        properties.isEnabled(),
        properties.getTimeoutSeconds(),
        properties.getMaxResults(),
        providerSummaries.size(),
        providerSummaries);
  }
}
