package dev.mikoto2000.rei.attention;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Slack通知を用途や設定に応じて提供する窓口。
 *
 * <p>Slack Web API adapter: HTTP success alone is not a delivery receipt.
 */
@Component
@ConditionalOnProperty(name = "rei.attention.slack.enabled", havingValue = "true")
@EnableConfigurationProperties(SlackNotificationProperties.class)
public class SlackNotificationProvider implements NotificationProvider, AutoCloseable {
  /** この機能の動作設定。 */
  private final SlackNotificationProperties properties;

  /** クライアント。 */
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(2))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper mapper =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(4096)
                          .maxNumberLength(64)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  /** 指定された値や利用先を使って、SlackNotificationProviderを初期化する。 */
  public SlackNotificationProvider(SlackNotificationProperties properties) {
    this.properties = properties;
  }

  /** 注意の管理で用いる名前を返す。 */
  @Override
  public String name() {
    return "SLACK";
  }

  /** 注意の管理で用いる最小実行間隔ミリ秒数を返す。 */
  @Override
  public long minimumIntervalMillis() {
    return 1000;
  }

  /** 注意の管理で用いる配信先を返す。 */
  @Override
  public String destination() {
    return digest(
        name()
            + ":"
            + properties.endpoint()
            + ":"
            + properties.channel()
            + ":"
            + digest(properties.botToken() == null ? "" : properties.botToken()));
  }

  /** 注意の管理で用いる内容の照合値を返す。 */
  static String digest(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 注意の管理で用いるdeliverを返す。 */
  @Override
  public Receipt deliver(String id, String metadata) throws Exception {
    if (Thread.currentThread().isInterrupted())
      throw new InterruptedException("Notification delivery cancelled before send");
    if (!properties.enabled()) throw new IllegalArgumentException("Slack delivery is disabled");
    UUID.fromString(id);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (metadata == null || metadata.length() > 4096)
      throw new IllegalArgumentException("Invalid notification metadata");
    JsonNode source = mapper.readTree(metadata);
    var fields = new HashSet<String>();
    source.fieldNames().forEachRemaining(fields::add);
    if (!source.isObject()
        || !fields.equals(Set.of("schemaVersion", "id", "projectId", "kind", "createdAt"))
        || !source.path("schemaVersion").isIntegralNumber()
        || source.path("schemaVersion").asLong() != 1
        || !id.equals(source.path("id").asText())
        || !source.path("projectId").isTextual()
        || !source.path("kind").asText().matches("[A-Z_]{1,64}")
        || source.path("projectId").asText().length() > 128
        || !source.path("createdAt").isTextual())
      throw new IllegalArgumentException("Only bounded notification metadata can be sent");
    java.time.Instant.parse(source.path("createdAt").asText());
    String text =
        "Rei "
            + source.path("kind").asText()
            + "; Project "
            + source.path("projectId").asText()
            + "; notification "
            + id
            + "; at "
            + source.path("createdAt").asText();
    String payload =
        mapper.writeValueAsString(
            Map.of(
                "channel",
                properties.channel(),
                "client_msg_id",
                id,
                "text",
                text,
                "mrkdwn",
                false,
                "parse",
                "none",
                "unfurl_links",
                false,
                "unfurl_media",
                false));
    var request =
        HttpRequest.newBuilder(URI.create(properties.endpoint()))
            .timeout(Duration.ofSeconds(2))
            .header("Authorization", "Bearer " + properties.botToken())
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();
    var headers = new java.util.concurrent.atomic.AtomicReference<HttpResponse.ResponseInfo>();
    var pending =
        client.sendAsync(
            request,
            info -> {
              headers.set(info);
              return new LimitedSubscriber();
            });
    try {
      var response = pending.get(2, TimeUnit.SECONDS);
      int code = response.statusCode();
      if (code == 429) return rateLimited(response.headers());
      if (code >= 500) return new Receipt("UNKNOWN", "slack_server_outcome_unknown", "", 0);
      if (code < 200 || code >= 300) return new Receipt("FAILED", "http_" + code, "", 0);
      JsonNode result;
      try {
        result = mapper.readTree(response.body());
      } catch (java.io.IOException error) {
        return unknown();
      }
      if (result == null || !result.isObject() || !result.path("ok").isBoolean()) return unknown();
      if (result.path("ok").asBoolean()) {
        String channel = result.path("channel").asText(), ts = result.path("ts").asText();
        if (!properties.channel().equals(channel) || !ts.matches("[0-9]{1,20}\\.[0-9]{1,10}"))
          return unknown();
        return new Receipt("SENT", "slack_message_confirmed", channel + ":" + ts, 0);
      }
      String error = result.path("error").asText();
      if (Set.of("internal_error", "fatal_error", "request_timeout", "service_unavailable")
          .contains(error)) return unknown();
      if (Set.of("rate_limited", "ratelimited").contains(error))
        return new Receipt("FAILED", "slack_rate_limited", "", 60);
      if (Set.of(
              "invalid_auth",
              "not_authed",
              "token_revoked",
              "token_expired",
              "account_inactive",
              "channel_not_found",
              "not_in_channel",
              "missing_scope",
              "no_permission",
              "is_archived",
              "invalid_arguments",
              "no_text")
          .contains(error)) return new Receipt("FAILED", "slack_rejected", "", 0);
      return unknown();
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw error;
    } catch (ExecutionException | TimeoutException error) {
      var received = headers.get();
      return received != null && received.statusCode() == 429
          ? rateLimited(received.headers())
          : unknown();
    } finally {
      pending.cancel(true);
    }
  }

  /** 確定できない結果を、判定不能の情報として表す。 */
  private Receipt unknown() {
    return new Receipt("UNKNOWN", "slack_outcome_unknown", "", 0);
  }

  /** 注意の管理で用いる頻度制限されたを返す。 */
  private Receipt rateLimited(HttpHeaders headers) {
    int seconds = 60;
    try {
      seconds =
          Math.max(
              1, Math.min(3600, Integer.parseInt(headers.firstValue("Retry-After").orElse("60"))));
    } catch (NumberFormatException ignored) {
    }
    return new Receipt("FAILED", "slack_rate_limited", "", seconds);
  }

  /** 注意の管理に必要な制限された購読先を扱う。 */
  private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
    /** 委譲先。 */
    private final HttpResponse.BodySubscriber<byte[]> delegate =
        HttpResponse.BodySubscribers.ofByteArray();

    /** 購読。 */
    private java.util.concurrent.Flow.Subscription subscription;

    /** 件数。 */
    private long count;

    /** 停止済み。 */
    private boolean stopped;

    /** 注意の管理で参照する本文を返す。 */
    public CompletionStage<byte[]> getBody() {
      return delegate.getBody();
    }

    /** 通知に応じて状態や表示を更新するため、購読を受け付ける。 */
    public void onSubscribe(java.util.concurrent.Flow.Subscription incoming) {
      subscription = incoming;
      delegate.onSubscribe(incoming);
    }

    /** 通知に応じて状態や表示を更新するため、次を受け付ける。 */
    public void onNext(List<java.nio.ByteBuffer> buffers) {
      if (stopped) return;
      for (var buffer : buffers) count += buffer.remaining();
      if (count > 8192) {
        stopped = true;
        delegate.onError(new java.io.IOException("Slack response limit exceeded"));
        subscription.cancel();
        return;
      }
      delegate.onNext(buffers);
    }

    /** 通知に応じて状態や表示を更新するため、エラーを受け付ける。 */
    public void onError(Throwable error) {
      if (!stopped) {
        stopped = true;
        delegate.onError(error);
      }
    }

    /** 通知に応じて状態や表示を更新するため、完了を受け付ける。 */
    public void onComplete() {
      if (!stopped) {
        stopped = true;
        delegate.onComplete();
      }
    }
  }

  /** 注意の管理で利用した資源や登録を解放し、利用を終了する。 */
  @jakarta.annotation.PreDestroy
  @Override
  public void close() {
    client.shutdownNow();
  }
}
