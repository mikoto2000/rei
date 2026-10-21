package dev.mikoto2000.rei.paper;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 論文の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Data
@Component
@ConfigurationProperties(prefix = "rei.paper")
public class PaperProperties {
  /** 既定要約動作モード。 */
  private PaperSummary.Mode defaultSummaryMode = PaperSummary.Mode.STANDARD;

  /** 既定翻訳動作モード。 */
  private PaperTranslation.Mode defaultTranslationMode = PaperTranslation.Mode.TECHNICAL;

  /** 有効状態。 */
  private boolean enabled = true;

  /** 最大復元済み応答バイト数。 */
  private int maxDecodedResponseBytes = 8_000_000;

  /** 最大復元済みPDFバイト数。 */
  private int maxDecodedPdfBytes = 20_000_000;

  /** 最大転送回数。 */
  private int maxRedirects = 5;

  /** 接続タイムアウト／読み取りタイムアウト。 */
  private Duration connectTimeout = Duration.ofSeconds(5), readTimeout = Duration.ofSeconds(10);

  /** 最大PDFバイト数／最大応答バイト数／最大ページ一覧／最大抽出済み文字数／最大節一覧／最大節文字数。 */
  private int maxPdfBytes = 20_000_000,
      maxResponseBytes = 4_000_000,
      maxPages = 200,
      maxExtractedChars = 1_000_000,
      maxSections = 500,
      maxSectionChars = 16000;

  /** 翻訳文書の分割単位大きさ／要約入力上限／最大保管資料検索結果一覧／既定上限／最大上限／再試行回数。 */
  private int translationChunkSize = 4000,
      summaryInputLimit = 16000,
      maxLibrarySearchResults = 50,
      defaultLimit = 10,
      maxLimit = 100,
      retries = 2;

  /** タイムアウト／言語モデルタイムアウト／再試行の待ち時間。 */
  private Duration timeout = Duration.ofSeconds(30),
      llmTimeout = Duration.ofMinutes(3),
      backoff = Duration.ofMillis(500);

  /** 取得元に伝えるクライアント情報／開始AlexAPIキー／プロンプト形式のバージョン／用語集形式のバージョン。 */
  private String userAgent = "Rei-PaperResearch/1.0",
      openAlexApiKey = "",
      promptVersion = "1",
      glossaryVersion = "1";

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate() {
    new dev.mikoto2000.rei.http.HttpFetchPolicy(
        maxResponseBytes,
        maxDecodedResponseBytes,
        connectTimeout,
        readTimeout,
        timeout,
        maxRedirects,
        null,
        null,
        false);
    new dev.mikoto2000.rei.http.HttpFetchPolicy(
        maxPdfBytes,
        maxDecodedPdfBytes,
        connectTimeout,
        readTimeout,
        timeout,
        maxRedirects,
        null,
        null,
        false);
    if (maxPdfBytes < 1
        || maxResponseBytes < 1
        || maxPages < 1
        || maxExtractedChars < 1
        || maxSections < 1
        || maxSectionChars < 1
        || translationChunkSize < 1
        || summaryInputLimit < 1000
        || maxLibrarySearchResults < 1
        || maxLibrarySearchResults > 100
        || maxLimit < 1
        || maxLimit > 100
        || defaultLimit < 1
        || defaultLimit > maxLimit
        || retries < 0
        || retries > 5
        || timeout.isNegative()
        || timeout.isZero()
        || llmTimeout.isNegative()
        || llmTimeout.isZero()
        || backoff.isNegative())
      throw new IllegalArgumentException("Invalid rei.paper resource limits");
  }
}
