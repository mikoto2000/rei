package dev.mikoto2000.rei.http;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;

/**
 * 外部情報の取得で受け渡すHTTP通信取得判断基準を表す。
 *
 * @param maxWireBytes 最大機能の接続バイト数
 * @param maxDecodedBytes 最大復元済みバイト数
 * @param connectTimeout 接続タイムアウト
 * @param readTimeout 読み取りタイムアウト
 * @param totalTimeout 合計タイムアウト
 * @param maxRedirects 最大転送回数
 * @param requiredContentType 必須内容の種類
 * @param configuredOrigin 設定済み起点
 * @param allowPrivateOrigin 許可非公開起点
 */
public record HttpFetchPolicy(
    int maxWireBytes,
    int maxDecodedBytes,
    Duration connectTimeout,
    Duration readTimeout,
    Duration totalTimeout,
    int maxRedirects,
    String requiredContentType,
    URI configuredOrigin,
    boolean allowPrivateOrigin) {
  /** 受け取った情報からHttpFetchPolicyを作成する。 */
  public HttpFetchPolicy {
    if (maxWireBytes <= 0
        || maxDecodedBytes <= 0
        || maxWireBytes > 100 * 1024 * 1024
        || maxDecodedBytes > 100 * 1024 * 1024
        || maxRedirects < 0
        || maxRedirects > 10) throw new IllegalArgumentException("HTTP fetch limits");
    for (var duration : new Duration[] {connectTimeout, readTimeout, totalTimeout})
      if (duration == null
          || duration.isZero()
          || duration.isNegative()
          || duration.compareTo(Duration.ofMinutes(5)) > 0)
        throw new IllegalArgumentException("HTTP fetch timeout");
    if (configuredOrigin != null) PublicNetworkPolicy.validateSyntax(configuredOrigin);
  }

  /** 外部情報の取得で用いるHTMLを返す。 */
  public static HttpFetchPolicy html(Duration timeout) {
    return new HttpFetchPolicy(
        2 * 1024 * 1024,
        4 * 1024 * 1024,
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        timeout,
        5,
        null,
        null,
        false);
  }

  /** 外部情報の取得で用いるポートを返す。 */
  private static int port(URI uri) {
    return uri.getPort() >= 0
        ? uri.getPort()
        : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public void validate(URI uri) {
    PublicNetworkPolicy.validateSyntax(uri);
    if (configuredOrigin != null) {
      if (!configuredOrigin.getScheme().equalsIgnoreCase(uri.getScheme())
          || !configuredOrigin.getHost().equalsIgnoreCase(uri.getHost())
          || port(configuredOrigin) != port(uri))
        throw new HttpFetchException(HttpFetchException.Code.URL_NOT_ALLOWED);
      if (allowPrivateOrigin || loopbackOrigin()) return;
    }
    PublicNetworkPolicy.validate(uri);
  }

  /** 外部情報の取得において、同じ端末起点に該当するか判定する。 */
  private boolean loopbackOrigin() {
    if (configuredOrigin == null) return false;
    if ("localhost".equalsIgnoreCase(configuredOrigin.getHost())) return true;
    InetAddress address = PublicNetworkPolicy.literal(configuredOrigin.getHost());
    return address != null && address.isLoopbackAddress();
  }

  /** 外部情報の取得において、allows接続先に該当するか判定する。 */
  public boolean allowsAddress(InetAddress address) {
    if (configuredOrigin != null && allowPrivateOrigin) return true;
    if (loopbackOrigin()) return address.isLoopbackAddress();
    return PublicNetworkPolicy.isPublic(address);
  }
}
