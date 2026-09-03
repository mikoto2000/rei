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

public class TopicCandidateParser {
  private static final Logger log = LoggerFactory.getLogger(TopicCandidateParser.class);

  private final JsonMapper jsonMapper;
  private final Clock clock;

  public TopicCandidateParser(JsonMapper jsonMapper, Clock clock) {
    this.jsonMapper = jsonMapper;
    this.clock = clock;
  }

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
    if (objectStart >= 0 && objectEnd > objectStart && (arrayStart < 0 || objectStart < arrayStart)) {
      return normalized.substring(objectStart, objectEnd + 1);
    }
    if (arrayStart >= 0 && arrayEnd > arrayStart) {
      return normalized.substring(arrayStart, arrayEnd + 1);
    }
    return normalized;
  }

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

  private String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? "" : value.asString();
  }

  private double number(JsonNode node, String field, double fallback) {
    JsonNode value = node.get(field);
    return value == null || !value.isNumber() ? fallback : value.asDouble();
  }

  private <T extends Enum<T>> T enumValue(Class<T> type, String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return null;
    }
  }
}
