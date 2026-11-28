package dev.mikoto2000.rei.autonomous;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Strict data decoding. A model decision never contains publication authority or executable fields.
 */
public final class AutonomousPostResponse {
  public sealed interface Decision permits Skip, Proposal {}

  public record Skip(String reason) implements Decision {}

  public record Proposal(AutonomousDraft.Content content) implements Decision {}

  private static final ObjectMapper JSON =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(8)
                          .maxStringLength(16384)
                          .build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private AutonomousPostResponse() {}

  public static Decision decode(String response, Set<String> suppliedSourceIds) {
    if (response == null
        || response.isBlank()
        || response.length() > 16384
        || suppliedSourceIds == null) throw invalid();
    JsonNode root;
    try {
      root = JSON.readTree(response);
    } catch (IOException malformed) {
      // Parser exceptions may retain raw response fragments; do not propagate them into logs.
      throw invalid();
    }
    fields(root, Set.of("decision", "reason", "draft"));
    String decision = text(root.path("decision"));
    String reason = text(root.path("reason"));
    if (reason.isBlank()
        || reason.length() > 2048
        || !StandardCharsets.UTF_8.newEncoder().canEncode(reason)) throw invalid();
    AutonomousPostText.requireReadable(reason);
    if ("SKIP".equals(decision)) {
      if (root.has("draft") && !root.path("draft").isNull()) throw invalid();
      return new Skip(reason);
    }
    if (!"PROPOSE_POST".equals(decision)) throw invalid();
    var draft = root.path("draft");
    fields(draft, Set.of("text", "sourceIds"));
    String body = text(draft.path("text"));
    var identifiers = draft.path("sourceIds");
    if (!identifiers.isArray() || identifiers.isEmpty() || identifiers.size() > 64) throw invalid();
    var actual = new ArrayList<String>();
    var distinct = new HashSet<String>();
    for (var identifier : identifiers) {
      String id = text(identifier);
      if (!suppliedSourceIds.contains(id) || !distinct.add(id)) throw invalid();
      actual.add(id);
    }
    return new Proposal(AutonomousDraft.Content.create(1, body, reason, actual));
  }

  private static String text(JsonNode value) {
    if (!value.isTextual()) throw invalid();
    return value.textValue();
  }

  private static void fields(JsonNode value, Set<String> allowed) {
    if (value == null || !value.isObject()) throw invalid();
    value
        .fieldNames()
        .forEachRemaining(field -> {
          if (!allowed.contains(field)) throw invalid();
        });
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("Invalid autonomous model response");
  }
}
