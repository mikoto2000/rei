package dev.mikoto2000.rei.core.policy;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure, bounded required-field checks against the registered callback's own input contract. */
public final class ToolSchemaArguments {
  private static final int LIMIT = 4_194_304;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(64)
                          .maxStringLength(LIMIT)
                          .maxNumberLength(1000)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private ToolSchemaArguments() {}

  public static boolean supports(String schema) {
    var node = schema(schema);
    return node != null && node.path("required").isArray() && !node.path("required").isEmpty();
  }

  static JsonNode input(String tool, String input) {
    try {
      if (input == null || input.isBlank() || input.length() > LIMIT)
        throw new IllegalArgumentException();
      var node = JSON.readTree(input);
      if (node == null || !node.isObject()) throw new IllegalArgumentException();
      return node;
    } catch (RuntimeException invalid) {
      throw new InvalidToolArgumentsException(
          tool
              + ": specify one bounded JSON object without duplicate keys or trailing data. "
              + "The tool has not executed; correct its arguments, keeping the registered field names.");
    }
  }

  public static void validate(String tool, String schema, String input) {
    if (!supports(schema)) return;
    var contract = schema(schema);
    var problems = new ArrayList<String>();
    check(contract, contract, input(tool, input), "arguments", problems, 0, new int[1]);
    if (!problems.isEmpty())
      throw new InvalidToolArgumentsException(
          tool
              + ": "
              + String.join("; ", problems)
              + ". The tool has not executed. Supply the required fields in their declared objects; "
              + "correct only this call and preserve completed sibling results.");
  }

  private static JsonNode schema(String text) {
    try {
      if (text == null || text.length() > LIMIT) return null;
      var node = JSON.readTree(text);
      return node != null && node.isObject() ? node : null;
    } catch (RuntimeException invalidConfiguration) {
      // An invalid/opaque callback contract is not a model argument error to repair.
      return null;
    }
  }

  private static void check(
      JsonNode root,
      JsonNode schema,
      JsonNode value,
      String path,
      List<String> problems,
      int depth,
      int[] visited) {
    if (problems.size() >= 8) return;
    if (depth > 48 || ++visited[0] > 10_000) {
      problems.add("argument shape exceeds the preflight limit");
      return;
    }
    if (schema.path("$ref").isString()) {
      String ref = schema.path("$ref").asString();
      // Never fetch remote schemas. Unknown unions/references remain the tool's responsibility.
      if (!ref.startsWith("#/")) return;
      schema = root.at(ref.substring(1));
    }
    if (schema.has("anyOf") || schema.has("oneOf")) return;
    if (value.isObject()) {
      var requiredNames = new java.util.HashSet<String>();
      for (var required : schema.path("required")) {
        if (!required.isString()) continue;
        String field = required.asString();
        requiredNames.add(field);
        var child = value.path(field);
        String at = path + "." + field;
        if (at.length() > 256) at = "required field";
        if (child.isMissingNode()) problems.add(at + " is required");
        else
          check(
              root, schema.path("properties").path(field), child, at, problems, depth + 1, visited);
        if (problems.size() >= 8) return;
      }
      // Optional nulls retain their existing default semantics; inspect present object shapes only.
      for (var entry : value.properties()) {
        var child = entry.getValue();
        if (!requiredNames.contains(entry.getKey())
            && (child.isObject() || child.isArray())
            && schema.path("properties").has(entry.getKey()))
          check(
              root,
              schema.path("properties").path(entry.getKey()),
              child,
              path + "." + entry.getKey(),
              problems,
              depth + 1,
              visited);
      }
    } else if (value.isArray()) {
      if (value.size() > 10_000) {
        problems.add("argument array exceeds the preflight limit");
        return;
      }
      int i = 0;
      for (var child : value) {
        if (child.isObject())
          check(
              root,
              schema.path("items"),
              child,
              path + "[" + i + "]",
              problems,
              depth + 1,
              visited);
        if (++i > 10_000 || problems.size() >= 8) break;
      }
    }
    String type = schema.path("type").isString() ? schema.path("type").asString() : "";
    boolean valid =
        switch (type) {
          case "object" -> value.isObject();
          case "array" -> value.isArray();
          case "string" -> value.isString();
          case "integer" -> value.isIntegralNumber();
          case "number" -> value.isNumber();
          case "boolean" -> value.isBoolean();
          case "null" -> value.isNull();
          default -> true;
        };
    if (!valid && problems.size() < 8) problems.add(path + " must be " + type);
  }
}
