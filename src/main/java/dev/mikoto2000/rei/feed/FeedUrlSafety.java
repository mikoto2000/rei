package dev.mikoto2000.rei.feed;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * フィードの購読と収集に必要なフィードURLSafetyを扱う。
 *
 * <p>Reject credential-bearing feed URLs before persistence, display or network access.
 */
public final class FeedUrlSafety {
  /** 認証情報キー一覧。 */
  private static final Set<String> CREDENTIAL_KEYS =
      Set.of(
          "token",
          "accesstoken",
          "authtoken",
          "bearertoken",
          "key",
          "apikey",
          "accesskey",
          "auth",
          "authorization",
          "secret",
          "password",
          "passwd",
          "credential",
          "credentials",
          "signature",
          "sig",
          "clientsecret",
          "refreshtoken");

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private FeedUrlSafety() {}

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public static URI parse(String value) {
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (value == null || value.length() > 2048) throw invalid();
      URI uri = URI.create(value);
      if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getRawUserInfo() != null
          || uri.getRawFragment() != null
          || uri.getPort() == 0
          || uri.getPort() > 65535) throw invalid();
      if (uri.getRawQuery() != null) {
        for (String parameter : uri.getRawQuery().split("[&;]")) {
          String key =
              URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8)
                  .toLowerCase(Locale.ROOT)
                  .replaceAll("[-_]", "");
          if (CREDENTIAL_KEYS.contains(key)) throw invalid();
        }
      }
      return uri;
    } catch (IllegalArgumentException e) {
      // URI and decoder exceptions can contain the original URL or credentials.
      throw invalid();
    }
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("フィード URL が不正です。HTTP(S) URL を使い、認証情報・fragment を含めないでください");
  }
}
