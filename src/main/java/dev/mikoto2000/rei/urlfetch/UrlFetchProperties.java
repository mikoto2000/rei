package dev.mikoto2000.rei.urlfetch;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** URL取得の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.url-fetch")
public class UrlFetchProperties {
  /** 最大機能の接続バイト数。 */
  private int maxWireBytes = 2 * 1024 * 1024;

  /** 最大復元済みバイト数。 */
  private int maxDecodedBytes = 4 * 1024 * 1024;

  /** 接続タイムアウトの秒数。 */
  private int connectTimeoutSeconds = 5;

  /** 読み取りタイムアウトの秒数。 */
  private int readTimeoutSeconds = 10;

  /** タイムアウトの秒数。 */
  private int timeoutSeconds = 30;

  /** 最大転送回数。 */
  private int maxRedirects = 5;

  /** 外部の情報を利用できるよう、判断基準を取得する。 */
  public dev.mikoto2000.rei.http.HttpFetchPolicy fetchPolicy() {
    return new dev.mikoto2000.rei.http.HttpFetchPolicy(
        maxWireBytes,
        maxDecodedBytes,
        java.time.Duration.ofSeconds(connectTimeoutSeconds),
        java.time.Duration.ofSeconds(readTimeoutSeconds),
        java.time.Duration.ofSeconds(timeoutSeconds),
        maxRedirects,
        null,
        null,
        false);
  }
}
