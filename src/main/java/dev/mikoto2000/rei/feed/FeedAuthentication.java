package dev.mikoto2000.rei.feed;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Exact-feed bindings, validated independently of secret resolution. */
@Component
public class FeedAuthentication {
  private final Map<URI, String> environmentNames;
  private final Function<String, String> environment;

  @Autowired
  public FeedAuthentication(FeedProperties properties) {
    this(properties, System::getenv, false);
  }

  // HTTP opt-in is deliberately test-only, not exposed as a runtime property.
  FeedAuthentication(FeedProperties properties, Function<String, String> environment, boolean allowLoopbackHttp) {
    this.environment = environment;
    Map<URI, String> bindings = new HashMap<>();
    if (properties.authentication().size() > 100) throw invalidConfiguration();
    for (var binding : properties.authentication()) {
      try {
        URI url = FeedUrlSafety.parse(binding.url());
        URI origin = FeedUrlSafety.parse(binding.allowedOrigin());
        boolean https = "https".equalsIgnoreCase(url.getScheme());
        boolean localFixture = allowLoopbackHttp && "http".equalsIgnoreCase(url.getScheme())
            && ("127.0.0.1".equals(url.getHost()) || "[::1]".equals(url.getHost()));
        if ((!https && !localFixture) || binding.allowedOrigin().length() > 512
            || !origin.getRawPath().isEmpty() || origin.getRawQuery() != null
            || !url.getScheme().equalsIgnoreCase(origin.getScheme())
            || !url.getHost().equalsIgnoreCase(origin.getHost()) || port(url) != port(origin)
            || binding.tokenEnv() == null || !binding.tokenEnv().matches("[A-Z_][A-Z0-9_]{0,127}")
            || bindings.putIfAbsent(url, binding.tokenEnv()) != null) throw invalidConfiguration();
      } catch (IllegalArgumentException | NullPointerException e) {
        // Neither binding values nor nested exception messages are safe to echo.
        throw invalidConfiguration();
      }
    }
    environmentNames = Map.copyOf(bindings);
  }

  Optional<String> bearerToken(URI uri) {
    String name = environmentNames.get(uri);
    if (name == null) return Optional.empty();
    String token;
    try {
      token = environment.apply(name);
    } catch (RuntimeException e) {
      throw unavailable();
    }
    if (token == null || !token.matches("[A-Za-z0-9_-]{43,128}")) throw unavailable();
    return Optional.of(token);
  }

  private static int port(URI uri) {
    return uri.getPort() == -1 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
  }

  private static FeedFetchException unavailable() {
    return new FeedFetchException("フィード認証用の環境変数が未設定または不正です。token-env 設定と読み取り専用 token を確認してください", null);
  }

  private static IllegalArgumentException invalidConfiguration() {
    return new IllegalArgumentException("rei.feed.authentication が不正です。URL・HTTPS origin・環境変数名・重複・件数を確認してください");
  }
}
