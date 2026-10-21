package dev.mikoto2000.rei.slack;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Slackとの連携に必要なSlack共通の通知属性Receiverを扱う。
 *
 * <p>Returns an envelope ID only after durable admission; raw payloads are never logged.
 */
public final class SlackEnvelopeReceiver {
  /** 実行環境。 */
  private final SlackRuntime runtime;

  /** チーム。 */
  private final String team;

  /** Slack上で共同作業の案内を行う窓口。 */
  private final SlackAgentService collaboration;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json =
      new ObjectMapper(
          JsonFactory.builder()
              .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
              .streamReadConstraints(
                  StreamReadConstraints.builder()
                      .maxNestingDepth(16)
                      .maxStringLength(65536)
                      .build())
              .build());

  /** Slackとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SlackEnvelopeReceiver(SlackRuntime runtime, String team) {
    this(runtime, team, null);
  }

  /** 指定された値や利用先を使って、SlackEnvelopeReceiverを初期化する。 */
  public SlackEnvelopeReceiver(SlackRuntime runtime, String team, SlackAgentService collaboration) {
    this.runtime = runtime;
    this.team = team;
    this.collaboration = collaboration;
  }

  /** Slackとの連携で用いる受信を返す。 */
  public String receive(String raw) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (raw == null || raw.length() > 262144) return null;
    try {
      var envelope = json.readTree(raw);
      if (!"events_api".equals(envelope.path("type").asText())) return null;
      String id = value(envelope, "envelope_id");
      if (id == null || id.isBlank()) return null;
      var payload = envelope.path("payload");
      var event = payload.path("event");
      if (!team.equals(value(payload, "team_id"))
          || !"event_callback".equals(value(payload, "type"))) return id;
      String type = value(event, "type");
      if (!"message".equals(type) && !"app_mention".equals(type)) return id;
      var message =
          new SlackMessage(
              value(payload, "event_id"),
              team,
              value(event, "channel"),
              value(event, "ts"),
              value(event, "thread_ts"),
              value(event, "user"),
              value(event, "bot_id"),
              value(event, "subtype"),
              type,
              value(event, "channel_type"),
              value(event, "text"));
      // Sender classification precedes human admission. A rejected external reply never falls back
      // to a new Run, including bot_user-only events and bot messages without a bot_id.
      if (isBotMessage(message)) {
        if (collaboration != null) collaboration.receive(message);
      } else runtime.accept(message);
      return id;
    } catch (Exception failed) {
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Slack envelope not acknowledged: validation or persistence unavailable (details omitted)");
      return null;
    }
  }

  /** Slackとの連携において、応答担当メッセージかどうかを判定する。 */
  private boolean isBotMessage(SlackMessage message) {
    return message.botId() != null
        || "bot_message".equals(message.subtype())
        || collaboration != null && collaboration.isBotSender(message.user());
  }

  /** 後続の処理で使う値を返す。 */
  private static String value(JsonNode node, String key) {
    var v = node.get(key);
    return v != null && v.isTextual() ? v.asText() : null;
  }
}
