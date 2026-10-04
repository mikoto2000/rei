package dev.mikoto2000.rei.feed;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/** Reject credential-bearing feed URLs before persistence, display or network access. */
public final class FeedUrlSafety {
  private static final Set<String> CREDENTIAL_KEYS = Set.of("token", "accesstoken", "authtoken", "bearertoken",
      "key", "apikey", "accesskey", "auth", "authorization", "secret", "password", "passwd", "credential",
      "credentials", "signature", "sig", "clientsecret", "refreshtoken");

  private FeedUrlSafety() {}

  public static URI parse(String value) {
    try {
      if (value == null || value.length() > 2048) throw invalid();
      URI uri = URI.create(value);
      if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null
          || uri.getPort() == 0 || uri.getPort() > 65535) throw invalid();
      if (uri.getRawQuery() != null) {
        for (String parameter : uri.getRawQuery().split("[&;]")) {
          String key = URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8)
              .toLowerCase(Locale.ROOT).replaceAll("[-_]", "");
          if (CREDENTIAL_KEYS.contains(key)) throw invalid();
        }
      }
      return uri;
    } catch (IllegalArgumentException e) {
      // URI and decoder exceptions can contain the original URL or credentials.
      throw invalid();
    }
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("フィード URL が不正です。HTTP(S) URL を使い、認証情報・fragment を含めないでください");
  }
}
