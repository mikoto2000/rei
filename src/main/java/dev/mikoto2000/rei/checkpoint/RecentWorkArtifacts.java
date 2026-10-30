package dev.mikoto2000.rei.checkpoint;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Bounded current tails of successfully written artifacts, never proof of task completion. */
final class RecentWorkArtifacts {
  private RecentWorkArtifacts() {}

  static String describe(
      PersistentCheckpoint state,
      java.util.function.BiFunction<String, String, ?> rawReader,
      int budget) {
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var excerpts = new ArrayList<Map<String, Object>>();
    if (rawReader == null || budget < 2) return "[]";
    var seen = new java.util.HashSet<Path>();
    var operations = state.operations();
    int inspected = 0;
    for (int i = operations.size() - 1;
        i >= Math.max(0, operations.size() - 64) && inspected < 4 && excerpts.size() < 2;
        i--) {
      CheckpointReconciler.active();
      var operation = operations.get(i);
      if (!operation.toolName().equals("writeMultiFile")
          || operation.status() != PersistentCheckpoint.OperationStatus.SUCCEEDED) continue;
      inspected++;
      var reference =
          state.evidence().stream()
              .filter(
                  e ->
                      e.origin().equals("RAW_RESULT_REFERENCE")
                          && Objects.equals(e.toolCallId(), operation.toolCallId())
                          && Objects.equals(e.runId(), operation.runId()))
              .findFirst();
      if (reference.isEmpty()) continue;
      try {
        Map<?, ?> stored =
            json.convertValue(
                rawReader.apply(state.sessionId(), reference.get().summary()), Map.class);
        if (!operation.toolName().equals(stored.get("toolName"))
            || !operation.toolCallId().equals(stored.get("toolCallId"))
            || !(stored.get("rawResult") instanceof String body)
            || body.length() > 65536) continue;
        var result = json.readTree(body);
        if (result == null || !result.isArray()) continue;
        Path root = Path.of(state.projectRoot()).toRealPath();
        for (var file : result) {
          if (excerpts.size() >= 2) break;
          if (!file.path("success").asBoolean()
              || !file.path("error").asText("").isBlank()
              || file.path("path").asText().isBlank()) continue;
          Path path = root.resolve(file.path("path").asText()).normalize();
          if (!path.startsWith(root)) continue;
          path = path.toRealPath();
          if (!path.startsWith(root) || !Files.isRegularFile(path) || !seen.add(path)) continue;
          byte[] bytes;
          try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(1024 * 1024 + 1);
          }
          if (bytes.length > 1024 * 1024) continue;
          String content =
              StandardCharsets.UTF_8
                  .newDecoder()
                  .decode(java.nio.ByteBuffer.wrap(bytes))
                  .toString();
          var lines = content.lines().toList();
          var tail = new ArrayList<String>();
          var excerpt = new LinkedHashMap<String, Object>();
          excerpt.put("path", root.relativize(path).toString().replace('\\', '/'));
          excerpt.put(
              "version",
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
          excerpt.put("toolCallId", operation.toolCallId());
          excerpt.put("rawResultRef", reference.get().summary());
          excerpt.put("endLine", lines.size());
          excerpt.put("contentTail", tail);
          excerpt.put("partial", true);
          excerpts.add(excerpt);
          for (int line = lines.size() - 1; line >= 0; line--) {
            tail.addFirst(lines.get(line));
            excerpt.put("startLine", line + 1);
            if (json.valueToTree(excerpts).toString().length() > budget) {
              tail.removeFirst();
              excerpt.put("startLine", line + 2);
              break;
            }
          }
          if (tail.isEmpty()) excerpts.removeLast();
        }
      } catch (java.io.IOException
          | java.security.NoSuchAlgorithmException
          | RuntimeException unavailable) {
        // Missing, invalid, binary or out-of-scope artifacts supply no continuation facts.
      }
    }
    return json.valueToTree(excerpts).toString();
  }
}
