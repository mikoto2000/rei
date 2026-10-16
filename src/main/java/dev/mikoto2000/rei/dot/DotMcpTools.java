package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.storage.StorageDatabase;
import io.modelcontextprotocol.spec.McpSchema;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class DotMcpTools {
  public static final List<String> NAMES =
      List.of(
          "rei_requests_list", "rei_request_get", "rei_request_claim", "rei_request_result_submit");

  public static dev.mikoto2000.rei.core.policy.ActionCapability capability(String name) {
    return switch (name) {
      case "rei_requests_list", "rei_request_get" ->
          dev.mikoto2000.rei.core.policy.ActionCapability.INBOX_READ;
      case "rei_request_claim" -> dev.mikoto2000.rei.core.policy.ActionCapability.INBOX_CLAIM;
      case "rei_request_result_submit" ->
          dev.mikoto2000.rei.core.policy.ActionCapability.INBOX_RESULT_WRITE;
      default -> throw new DotException("INVALID_ARGUMENT");
    };
  }

  private final DotRequestRepository repo;
  private final Set<String> allowed;
  private final boolean enabled;

  public DotMcpTools(DotRequestRepository repo, Set<String> allowed, boolean enabled) {
    this.repo = repo;
    this.allowed = Set.copyOf(allowed);
    this.enabled = enabled;
  }

  private String sign(String text) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(repo.cursorKey(), "HmacSHA256"));
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Cursor signing failed");
    }
  }

  private String cursor(String scope, String status, String id) {
    String body =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString((scope + "\n" + status + "\n" + id).getBytes(StandardCharsets.UTF_8));
    return body + "." + sign(body);
  }

  private String decode(String cursor, String scope, String status) {
    try {
      String[] parts = cursor.split("\\.", -1);
      if (parts.length != 2
          || !java.security.MessageDigest.isEqual(
              sign(parts[0]).getBytes(StandardCharsets.UTF_8),
              parts[1].getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
      String[] fields =
          new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8)
              .split("\n", -1);
      if (fields.length != 3 || !fields[0].equals(scope) || !fields[1].equals(status))
        throw new IllegalArgumentException();
      UUID.fromString(fields[2]);
      return fields[2];
    } catch (IllegalArgumentException e) {
      throw new DotException("INVALID_ARGUMENT");
    }
  }

  public McpSchema.CallToolResult call(String name, Map<String, Object> a) {
    Map<String, Object> output;
    boolean error = false;
    String project = null, id = null;
    String correlation = UUID.randomUUID().toString();
    try {
      capability(name);
      if (!enabled) throw new DotException("FEATURE_DISABLED");
      if (a == null) a = Map.of();
      if (StorageDatabase.JSON.writeValueAsBytes(a).length > 65536)
        throw new DotException("INVALID_ARGUMENT");
      if (name.equals("rei_requests_list")) {
        DotPayload.keys(a, Set.of("project_id", "status", "limit", "cursor"));
        String filter = DotPayload.text(a, "project_id", false, 100);
        if (!filter.isEmpty() && !allowed.contains(filter)) throw new DotException("NOT_FOUND");
        Set<String> scope = filter.isEmpty() ? allowed : Set.of(filter);
        String scopeKey = String.join(",", new TreeSet<>(scope));
        String state = DotPayload.text(a, "status", false, 40);
        if (state.isEmpty()) state = "PENDING";
        int limit = DotPayload.integer(a.get("limit"), 5, 1, 20);
        String token = DotPayload.text(a, "cursor", false, 2000);
        String after = token.isEmpty() ? null : decode(token, scopeKey, state);
        var page = repo.list(scope, state, after, limit);
        var out = new LinkedHashMap<String, Object>();
        out.put("schema_version", 1);
        out.put("requests", page);
        out.put(
            "next_cursor",
            page.size() == limit
                ? cursor(scopeKey, state, (String) page.getLast().get("request_id"))
                : null);
        output = out;
      } else {
        id = DotPayload.id(a, "request_id");
        project =
            repo.project(id)
                .filter(allowed::contains)
                .orElseThrow(() -> new DotException("NOT_FOUND"));
        if (!repo.published(project, id)) throw new DotException("NOT_FOUND");
        output =
            switch (name) {
              case "rei_request_get" -> {
                DotPayload.keys(a, Set.of("request_id"));
                yield repo.get(project, id);
              }
              case "rei_request_claim" -> {
                DotPayload.keys(a, Set.of("request_id", "claim_id"));
                yield repo.claim(project, id, DotPayload.id(a, "claim_id"));
              }
              case "rei_request_result_submit" -> {
                DotPayload.keys(
                    a,
                    Set.of(
                        "request_id",
                        "claim_id",
                        "submission_id",
                        "outcome",
                        "summary",
                        "content",
                        "evidence",
                        "unresolved_items",
                        "acceptance_results"));
                String claim = DotPayload.id(a, "claim_id"),
                    submission = DotPayload.id(a, "submission_id");
                String fingerprint =
                    dev.mikoto2000.rei.conversation.ConversationAdmissionStore.hash(
                        StorageDatabase.JSON.writeValueAsString(a));
                var replay = repo.replayReceipt(project, id, submission, fingerprint);
                if (replay.isPresent()) yield replay.get();
                var request = repo.get(project, id);
                var normalized =
                    DotPayload.result(
                        a, (List<?>) request.getOrDefault("acceptance_criteria", List.of()));
                yield repo.submit(project, id, claim, submission, normalized, fingerprint);
              }
              default -> throw new DotException("INVALID_ARGUMENT");
            };
      }
    } catch (Exception failure) {
      String code =
          failure instanceof DotException e
              ? e.code
              : failure instanceof IllegalArgumentException ? "INVALID_ARGUMENT" : "INTERNAL_ERROR";
      output =
          Map.of(
              "schema_version",
              1,
              "error",
              Map.of(
                  "code",
                  code,
                  "message",
                  code,
                  "retryable",
                  false,
                  "correlation_id",
                  correlation));
      error = true;
    }
    try {
      repo.audit(
          project,
          id,
          "local-mcp",
          NAMES.contains(name) ? name : "INVALID_TOOL",
          error ? ((Map<?, ?>) output.get("error")).get("code").toString() : "SUCCESS");
    } catch (RuntimeException unavailable) {
      output =
          Map.of(
              "schema_version",
              1,
              "error",
              Map.of(
                  "code",
                  "INTERNAL_ERROR",
                  "message",
                  "Audit unavailable",
                  "retryable",
                  false,
                  "correlation_id",
                  correlation));
      error = true;
    }
    try {
      return new McpSchema.CallToolResult(
          List.of(new McpSchema.TextContent(StorageDatabase.JSON.writeValueAsString(output))),
          error,
          output,
          null);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Map<String, Object> schema(String name) {
    var p = new LinkedHashMap<String, Object>();
    var required = new ArrayList<String>();
    if (name.equals("rei_requests_list")) {
      for (String k : List.of("project_id", "status", "cursor")) p.put(k, Map.of("type", "string"));
      p.put("limit", Map.of("type", "integer", "minimum", 1, "maximum", 20));
    } else {
      p.put("request_id", Map.of("type", "string"));
      required.add("request_id");
    }
    if (name.equals("rei_request_claim") || name.equals("rei_request_result_submit")) {
      p.put("claim_id", Map.of("type", "string"));
      required.add("claim_id");
    }
    if (name.equals("rei_request_result_submit")) {
      for (String k : List.of("submission_id", "outcome", "summary", "content"))
        p.put(k, Map.of("type", "string"));
      p.put(
          "outcome",
          Map.of("type", "string", "enum", List.of("COMPLETED", "PARTIAL", "FAILED", "UNKNOWN")));
      p.put(
          "unresolved_items",
          Map.of("type", "array", "maxItems", 20, "items", Map.of("type", "string")));
      p.put(
          "evidence",
          Map.of(
              "type",
              "array",
              "maxItems",
              20,
              "items",
              Map.of(
                  "type",
                  "object",
                  "properties",
                  Map.of(
                      "title",
                      Map.of("type", "string"),
                      "url",
                      Map.of("type", "string"),
                      "description",
                      Map.of("type", "string")),
                  "required",
                  List.of("title", "url"),
                  "additionalProperties",
                  false)));
      p.put(
          "acceptance_results",
          Map.of(
              "type",
              "array",
              "maxItems",
              16,
              "items",
              Map.of(
                  "type",
                  "object",
                  "properties",
                  Map.of(
                      "criterion_index",
                      Map.of("type", "integer"),
                      "status",
                      Map.of(
                          "type", "string", "enum", List.of("SATISFIED", "UNSATISFIED", "UNKNOWN")),
                      "evidence_indices",
                      Map.of("type", "array", "items", Map.of("type", "integer"))),
                  "required",
                  List.of("criterion_index", "status"),
                  "additionalProperties",
                  false)));
      required.addAll(List.of("submission_id", "outcome", "summary"));
    }
    return Map.of(
        "type", "object", "properties", p, "required", required, "additionalProperties", false);
  }
}
