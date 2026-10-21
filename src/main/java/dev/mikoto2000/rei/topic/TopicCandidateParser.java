package dev.mikoto2000.rei.topic;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 話題候補の入力を解釈し、機能内で扱える情報へ整える。 */
public class TopicCandidateParser {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(TopicCandidateParser.class);

  /** JSON変換データの変換。 */
  private final JsonMapper jsonMapper;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、TopicCandidateParserを初期化する。 */
  public TopicCandidateParser(JsonMapper jsonMapper, Clock clock) {
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public List<TopicCandidate> parse(String text, int maxCandidates) {
    if (text == null || text.isBlank() || maxCandidates <= 0) {
      return List.of();
    }
    try {
      JsonNode root = jsonMapper.readTree(normalizeJsonObject(text));
      JsonNode array = root.has("candidates") ? root.get("candidates") : root;
      if (!array.isArray()) return List.of();
      List<TopicCandidate> candidates = new ArrayList<>();
      for (JsonNode node : array) {
        if (candidates.size() >= maxCandidates) break;
        TopicCandidate candidate = parseCandidate(node);
        if (candidate != null) candidates.add(candidate);
      }
      return candidates;
    } catch (Exception e) {
      log.warn("Topic candidate JSON parse failed");
      return List.of();
    }
  }

  /** 同じ意味の指定を共通に扱えるよう、JSON変換対象データの表記をそろえる。 */
  static String normalizeJsonObject(String text) {
    String normalized = text.strip();
    if (normalized.startsWith("```")) {
      normalized = normalized.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
      normalized = normalized.replaceFirst("\\s*```$", "");
    }
    int objectStart = normalized.indexOf('{');
    int objectEnd = normalized.lastIndexOf('}');
    int arrayStart = normalized.indexOf('[');
    int arrayEnd = normalized.lastIndexOf(']');
    if (objectStart >= 0
        && objectEnd > objectStart
        && (arrayStart < 0 || objectStart < arrayStart)) {
      return normalized.substring(objectStart, objectEnd + 1);
    }
    if (arrayStart >= 0 && arrayEnd > arrayStart) {
      return normalized.substring(arrayStart, arrayEnd + 1);
    }
    return normalized;
  }

  /** 入力を機能内で扱えるよう、候補を解釈する。 */
  private TopicCandidate parseCandidate(JsonNode node) {
    String topic = text(node, "topic");
    if (topic.isBlank()) return null;
    TopicType type = enumValue(TopicType.class, text(node, "type"));
    TopicSource source = enumValue(TopicSource.class, text(node, "source"));
    if (type == null || source == null) return null;
    if (type != TopicType.UNFINISHED_WORK && type != TopicType.FOLLOW_UP) return null;
    return new TopicCandidate(
        UUID.randomUUID().toString(),
        topic,
        text(node, "reason"),
        type,
        source,
        number(node, "priority", 0.5d),
        number(node, "freshness", 0.5d),
        number(node, "usefulness", 0.5d),
        number(node, "intrusiveness", 0.5d),
        number(node, "confidence", 0.5d),
        Instant.now(clock));
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asString();
  }

  /** 話題の管理で用いる数値を返す。 */
  private double number(JsonNode node, String field, double fallback) {
    JsonNode value = node.get(field);
    return value == null || !value.isNumber() ? fallback : value.asDouble();
  }

  /** 話題の管理で用いるenum値を返す。 */
  private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
