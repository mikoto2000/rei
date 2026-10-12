package dev.mikoto2000.rei.subagent;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure preflight: no approval creation, child dispatch or argument regeneration. */
public final class DelegationArguments {
  private DelegationArguments() { }
  public static String optional(String value) { return value == null || value.isBlank() ? null : value; }
  public static void validate(String tool, String input) {
    if (!Set.of("delegateTask", "delegateTasks").contains(tool)) return;
    JsonNode root;
    try { root = JsonMapper.builder().build().readTree(input); }
    catch (RuntimeException error) { throw new IllegalArgumentException("Invalid delegation arguments: expected a JSON object"); }
    if (root == null || !root.isObject()) throw new IllegalArgumentException("Invalid delegation arguments: expected a JSON object");
    if (tool.equals("delegateTask")) { request(root, false, new HashSet<>()); return; }
    var requests = root.path("requests");
    if (!requests.isArray() || requests.isEmpty() || requests.size() > 8)
      throw new IllegalArgumentException("Invalid delegation arguments: requests must contain 1 to 8 items");
    var ids = new HashSet<String>();
    for (var request : requests) request(request, true, ids);
  }
  private static void request(JsonNode request, boolean batch, Set<String> ids) {
    if (!request.isObject()) throw new IllegalArgumentException("Invalid delegation arguments: each request must be an object");
    if (batch) {
      String id = text(request, "id");
      if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id))
        throw new IllegalArgumentException("Invalid delegation arguments: request id must be unique and contain 1 to 64 letters, digits, underscores or hyphens");
    }
    String agent = text(request, "agent"), task = text(request, "task"), context = text(request, "context");
    if (agent == null || task == null || task.length() > 16384 || context != null && context.length() > 32768)
      throw new IllegalArgumentException("Invalid delegation arguments: agent and task are required; task/context exceed the allowed length");
    String backend = text(request, "backendId");
    if (backend != null && !Set.of("local", "chatgpt-plan", "codex-app-server").contains(backend))
      throw new IllegalArgumentException("Invalid delegation arguments: backendId must be local, chatgpt-plan or codex-app-server; omit it for local execution");
    boolean local = backend == null || backend.equals("local");
    String account = text(request, "accountRef"), model = text(request, "modelId"), expected = text(request, "expectedOutput");
    boolean sources = array(request, "sourceRefs", 64, 4096);
    boolean constraints = array(request, "constraints", 16, 2048);
    boolean criteria = array(request, "completionCriteria", 16, 2048);
    if (local && (account != null || model != null || expected != null || sources || constraints || criteria))
      throw new IllegalArgumentException("Invalid local delegation arguments: omit accountRef, modelId, sourceRefs, expectedOutput, constraints and completionCriteria; put target files and output requirements in task/context");
    if (!local && (context != null || !sources || expected == null))
      throw new IllegalArgumentException("Invalid external delegation arguments: omit context and provide nonempty sourceRefs and expectedOutput");
  }
  private static String text(JsonNode request, String field) {
    var value = request.path(field);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isString()) throw new IllegalArgumentException("Invalid delegation arguments: " + field + " must be a string");
    return optional(value.asString());
  }
  private static boolean array(JsonNode request, String field, int max, int length) {
    var value = request.path(field);
    if (value.isMissingNode() || value.isNull()) return false;
    if (!value.isArray() || value.size() > max) throw new IllegalArgumentException("Invalid delegation arguments: " + field + " must be a bounded array");
    for (var item : value) if (!item.isString() || item.asString().isBlank() || item.asString().length() > length)
      throw new IllegalArgumentException("Invalid delegation arguments: " + field + " must contain nonempty bounded strings");
    return !value.isEmpty();
  }
}
