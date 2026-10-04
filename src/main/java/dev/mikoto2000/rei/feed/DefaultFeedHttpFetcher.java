package dev.mikoto2000.rei.feed;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.stereotype.Component;

@Component
public class DefaultFeedHttpFetcher implements FeedHttpFetcher {
  private final FeedAuthentication authentication;
  private final HttpClient publicClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NORMAL).build();
  private final HttpClient authenticatedClient = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.NEVER).build();

  public DefaultFeedHttpFetcher(FeedAuthentication authentication) {
    this.authentication = authentication;
  }

  @Override
  public FeedHttpResponse fetch(URI uri) {
    try {
      FeedUrlSafety.parse(uri.toString());
    } catch (IllegalArgumentException e) {
      throw new FeedFetchException("フィード URL が不正です。認証情報を URL に含めないでください", null);
    }
    var token = authentication.bearerToken(uri); // fail closed before constructing or sending a request
    try {
      var builder = HttpRequest.newBuilder(uri)
          .timeout(Duration.ofSeconds(30))
          .header("User-Agent", "rei-feed-fetcher/1.0").GET();
      token.ifPresent(value -> builder.header("Authorization", "Bearer " + value));
      HttpResponse<String> response = (token.isPresent() ? authenticatedClient : publicClient)
          .send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 401 || status == 403) {
        throw new FeedFetchException("フィード認証エラー (HTTP " + status
            + ")。環境変数の token・失効状態・発行元の読み取り権限を確認してください", status);
      }
      if (token.isPresent() && status >= 300 && status < 400) {
        throw new FeedFetchException("認証付きフィードの redirect は許可されません。最終 URL と origin 設定を確認してください", status);
      }
      // Never return error bodies, which can reflect headers or server diagnostics.
      String body = status >= 200 && status < 300 ? response.body() : "";
      if (token.isPresent()) body = body.replace(token.get(), "[redacted]");
      return new FeedHttpResponse(status, body);
    } catch (IOException | InterruptedException | IllegalArgumentException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      // Nested transport exceptions can expose the request, URL or Authorization header.
      throw new FeedFetchException("フィードの取得に失敗しました。接続先と設定を確認してください", null);
    }
  }
}
