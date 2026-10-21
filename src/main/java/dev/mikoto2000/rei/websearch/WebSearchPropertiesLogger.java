package dev.mikoto2000.rei.websearch;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Web上の情報の調査に必要なWeb検索設定ログの出力先を扱う。 */
@Component
public class WebSearchPropertiesLogger implements ApplicationRunner {

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(WebSearchPropertiesLogger.class);

  /** この機能の動作設定。 */
  private final WebSearchProperties properties;

  /** 指定された値や利用先を使って、WebSearchPropertiesLoggerを初期化する。 */
  public WebSearchPropertiesLogger(WebSearchProperties properties) {
    this.properties = properties;
  }

  /** Web上の情報の調査の操作を実行する。 */
  @Override
  public void run(ApplicationArguments args) {
    List<WebSearchProperties.ProviderProperties> providers = properties.getProviders();
    List<String> providerSummaries =
        providers == null
            ? List.of()
            : providers.stream()
                .filter(provider -> provider != null)
                .map(
                    provider ->
                        "%s(apiKeyConfigured=%s)"
                            .formatted(
                                provider.getName(),
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
