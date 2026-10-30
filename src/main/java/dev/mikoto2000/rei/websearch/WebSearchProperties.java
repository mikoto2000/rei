package dev.mikoto2000.rei.websearch;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Web検索の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.web-search")
public class WebSearchProperties {

  /** 有効状態。 */
  private boolean enabled = false;

  /** OpenAI search credentials are independent of chat authentication. */
  @org.springframework.beans.factory.annotation.Value("${OPENAI_API_KEY:}")
  private String openaiApiKey = "";

  /** タイムアウトの秒数。 */
  private int timeoutSeconds = 10;

  /** 接続タイムアウトの秒数。 */
  private int connectTimeoutSeconds = 5;

  /** 読み取りタイムアウトの秒数。 */
  private int readTimeoutSeconds = 10;

  /** 最大機能の接続バイト数。 */
  private int maxWireBytes = 2 * 1024 * 1024;

  /** 最大復元済みバイト数。 */
  private int maxDecodedBytes = 4 * 1024 * 1024;

  /** 最大転送回数。 */
  private int maxRedirects = 5;

  /** 外部の情報を利用できるよう、判断基準を取得する。 */
  public dev.mikoto2000.rei.http.HttpFetchPolicy fetchPolicy() {
    return fetchPolicy(null);
  }

  /** 外部の情報を利用できるよう、判断基準を取得する。 */
  public dev.mikoto2000.rei.http.HttpFetchPolicy fetchPolicy(ProviderProperties provider) {
    return new dev.mikoto2000.rei.http.HttpFetchPolicy(
        maxWireBytes,
        maxDecodedBytes,
        java.time.Duration.ofSeconds(connectTimeoutSeconds),
        java.time.Duration.ofSeconds(
            provider != null && provider.getTimeoutSeconds() != null
                ? provider.getTimeoutSeconds()
                : readTimeoutSeconds),
        java.time.Duration.ofSeconds(
            provider != null && provider.getTimeoutSeconds() != null
                ? provider.getTimeoutSeconds()
                : timeoutSeconds),
        maxRedirects,
        null,
        provider == null ? null : java.net.URI.create(provider.getBaseUrl()),
        provider != null && provider.isAllowPrivateNetwork());
  }

  /** 検索結果の件数上限。 */
  private int maxResults = 5;

  /** 最大検索検索条件一覧。 */
  private int maxSearchQueries = 3;

  /** 最大検索API呼び出し回数。 */
  private int maxSearchApiCalls = 4;

  /** 最大ページFetches。 */
  private int maxPageFetches = 5;

  /** 取得Parallelism。 */
  private int fetchParallelism = 3;

  /** 取得ごとのホスト。 */
  private int fetchPerHost = 2;

  /** 取得実行待ち許容件数。 */
  private int fetchQueueCapacity = 32;

  /** 取得まとめた処理タイムアウトの秒数。 */
  private int fetchBatchTimeoutSeconds = 30;

  /** ページ最大文字数。 */
  private int pageMaxCharacters = 2000;

  /** ページトークン数の上限。 */
  private int pageMaxTokens = 1500;

  /** 最大出力文字数。 */
  private int maxOutputCharacters = 16000;

  /** 最大出力トークン数。 */
  private int maxOutputTokens = 6000;

  /** 初期ページFetches。 */
  private int initialPageFetches = 2;

  /** 調査タイムアウトの秒数。 */
  private int researchTimeoutSeconds = 30;

  /** 合計機能の接続バイト数。 */
  private long totalWireBytes = 8L * 1024 * 1024;

  /** 合計復元済みバイト数。 */
  private long totalDecodedBytes = 16L * 1024 * 1024;

  /** 利用条件に合わない指定を受け付けないよう、選択内容を検証する。 */
  public void validateSelection() {
    if (maxResults < 1
        || maxResults > 20
        || maxSearchQueries < 1
        || maxSearchQueries > 3
        || maxSearchApiCalls < 1
        || maxSearchApiCalls > 12
        || maxPageFetches < 1
        || maxPageFetches > 20
        || fetchBatchTimeoutSeconds < 1
        || fetchBatchTimeoutSeconds > 300
        || fetchParallelism < 1
        || fetchParallelism > 3
        || fetchPerHost < 1
        || fetchPerHost > fetchParallelism
        || fetchQueueCapacity < 1
        || fetchQueueCapacity > 64)
      throw new IllegalArgumentException("Invalid web search selection limits");
    if (pageMaxCharacters < 128
        || pageMaxCharacters > 8000
        || pageMaxTokens < 128
        || pageMaxTokens > 6000
        || maxOutputCharacters < 512
        || maxOutputCharacters > 64000
        || maxOutputTokens < 256
        || maxOutputTokens > 16000
        || initialPageFetches < 1
        || initialPageFetches > 3
        || researchTimeoutSeconds < 1
        || researchTimeoutSeconds > 300
        || totalWireBytes < 1
        || totalWireBytes > 128L * 1024 * 1024
        || totalDecodedBytes < 1
        || totalDecodedBytes > 256L * 1024 * 1024)
      throw new IllegalArgumentException("Invalid bounded web research budgets");
  }

  /** 提供元一覧。 */
  private List<ProviderProperties> providers = defaultProviders();

  /** 提供元の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
  @Getter
  @Setter
  public static class ProviderProperties {

    /** 名前。 */
    private String name;

    /** 接続先の基準URL。 */
    private String baseUrl;

    /** APIキー。 */
    private String apiKey = "";

    /** Responses search model; independently configured from the chat LLM. */
    private String model = "gpt-4.1-mini";

    /** Search context budget supported by web_search. */
    private String searchContextSize = "medium";

    /** Maximum structured URLs adopted from this provider. */
    private int maxUrls = 20;

    /** Optional per-provider total/read timeout, in seconds. */
    private Integer timeoutSeconds;

    /** Allowed search domains, including their subdomains. */
    private List<String> allowedDomains = new ArrayList<>();

    /** Blocked search domains, including their subdomains. */
    private List<String> blockedDomains = new ArrayList<>();

    /** Resolve the default Responses endpoint only for the explicitly selected provider. */
    public String getBaseUrl() {
      return baseUrl == null && "openai".equalsIgnoreCase(name)
          ? "https://api.openai.com/v1/responses"
          : baseUrl;
    }

    /** 許可非公開ネットワーク。 */
    private boolean allowPrivateNetwork = false;
  }

  /** Web上の情報の調査で用いる既定提供元一覧を返す。 */
  private static List<ProviderProperties> defaultProviders() {
    ProviderProperties duckduckgo = new ProviderProperties();
    duckduckgo.setName("duckduckgo");
    duckduckgo.setBaseUrl("https://html.duckduckgo.com/html/");

    return new ArrayList<>(List.of(duckduckgo));
  }
}
