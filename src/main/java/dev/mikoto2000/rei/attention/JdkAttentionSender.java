package dev.mikoto2000.rei.attention;

import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * 注意の管理に必要なJdk注意Senderを扱う。
 *
 * <p>One application send attempt, no redirects and a bounded wall-clock wait. Response bodies are
 * discarded.
 */
@Component
public class JdkAttentionSender {
  /** クライアント。 */
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** 注意の管理で用いる送信を返す。 */
  public int send(AttentionDeliveryProperties properties, String id, String json) throws Exception {
    if (json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096)
      throw new IllegalArgumentException("Notification metadata is too large");
    var builder =
        HttpRequest.newBuilder(URI.create(properties.endpoint()))
            .timeout(Duration.ofSeconds(2))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", id)
            .POST(HttpRequest.BodyPublishers.ofString(json));
    if (properties.bearerToken() != null && !properties.bearerToken().isEmpty())
      builder.header("Authorization", "Bearer " + properties.bearerToken());
    var future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding());
    try {
      return future.get(2, TimeUnit.SECONDS).statusCode();
    } finally {
      future.cancel(true);
    }
  }

  /** 注意の管理で利用した資源や登録を解放し、利用を終了する。 */
  @PreDestroy
  public void close() {
    client.shutdownNow();
  }
}
