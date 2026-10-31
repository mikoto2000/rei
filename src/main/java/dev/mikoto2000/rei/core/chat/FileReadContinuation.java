package dev.mikoto2000.rei.core.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** 現在の要求に属する実際の返却ページから、欠けた継続引数だけを引き継ぐ。 */
final class FileReadContinuation {
  private static final ObjectMapper JSON =
      new ObjectMapper(
              com.fasterxml.jackson.core.JsonFactory.builder()
                  .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      com.fasterxml.jackson.core.StreamReadConstraints.builder()
                          .maxNestingDepth(64)
                          .maxStringLength(1_048_576)
                          .build())
                  .build())
          .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  private FileReadContinuation() {}

  static List<ToolCall> inherit(Prompt prompt, List<ToolCall> calls) {
    return calls.stream().map(call -> inherit(prompt, call)).toList();
  }

  private static ToolCall inherit(Prompt prompt, ToolCall call) {
    if (!call.name().equals("readMultiFile")) return call;
    try {
      if (call.arguments() == null || call.arguments().length() > 1_048_576) return call;
      var root = JSON.readTree(call.arguments());
      if (!root.isObject() || !root.path("files").isArray() || root.path("files").size() > 20)
        return call;
      boolean changed = false;
      for (var file : root.path("files")) {
        if (!(file instanceof ObjectNode request)
            || !request.path("path").isTextual()
            || !request.path("startLine").isIntegralNumber()
            || selected(request)) continue;
        var cursor = cursor(prompt, request);
        if (cursor == null) continue;
        // Explicit conflicting values describe a different read; do not reinterpret it.
        if (request.hasNonNull("offset") && !request.get("offset").equals(cursor.get("offset")))
          continue;
        if (request.hasNonNull("expectedVersion")
            && !request.get("expectedVersion").equals(cursor.get("expectedVersion"))) continue;
        if (request.hasNonNull("charset")
            && !Objects.equals(request.get("charset"), cursor.get("charset"))) continue;
        for (var field : List.of("offset", "expectedVersion", "charset", "purpose"))
          if (!request.hasNonNull(field) && cursor.hasNonNull(field)) {
            request.set(field, cursor.get(field));
            changed = true;
          }
      }
      return changed
          ? new ToolCall(call.id(), call.type(), call.name(), JSON.writeValueAsString(root))
          : call;
    } catch (java.io.IOException | RuntimeException malformed) {
      // Leave invalid input to the existing bounded argument-repair path.
      return call;
    }
  }

  private static boolean selected(JsonNode request) {
    return List.of("symbol", "section", "sectionId", "sectionPath", "headingLevel").stream()
        .anyMatch(request::hasNonNull);
  }

  private static JsonNode cursor(Prompt prompt, JsonNode request) throws java.io.IOException {
    var history = prompt.getInstructions();
    for (int i = history.size() - 1; i >= 0; i--) {
      var message = history.get(i);
      if (message instanceof UserMessage) break;
      if (!(message instanceof ToolResponseMessage tool)) continue;
      var matches = new ArrayList<JsonNode>();
      boolean seen = false;
      for (var response : tool.getResponses()) {
        if (!response.name().equals("readMultiFile")
            || !"FILE_CONTENT"
                .equals(
                    dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.capture(tool)
                        .policies()
                        .get(response.id()))) continue;
        var results = JSON.readTree(response.responseData());
        if (!results.isArray()) continue;
        for (var result : results) {
          if (!Objects.equals(result.get("path"), request.get("path"))) continue;
          seen = true;
          var next = result.path("nextRead");
          if (!result.path("truncated").asBoolean()
              || result.hasNonNull("error")
              || !next.isObject()
              || selected(next)
              || !Objects.equals(next.get("path"), request.get("path"))
              || !Objects.equals(next.get("startLine"), request.get("startLine"))
              || !next.path("offset").isIntegralNumber()
              || next.path("offset").asLong() < 0
              || !next.path("expectedVersion").isTextual()
              || !next.path("expectedVersion").asText().matches("[0-9a-f]{64}")
              || !Objects.equals(next.get("expectedVersion"), result.get("version"))) continue;
          if (!matches.contains(next)) matches.add(next);
        }
      }
      // Only the latest result batch for this path is authoritative. Never use an older cursor
      // after a completed read, error or a different pending range.
      if (seen) return matches.size() == 1 ? matches.getFirst() : null;
    }
    return null;
  }
}
