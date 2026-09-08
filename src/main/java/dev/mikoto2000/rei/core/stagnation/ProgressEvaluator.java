package dev.mikoto2000.rei.core.stagnation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.actionplan.ActionPlan;

/** Run-local evidence ledger. Unknown tools are conservative: success alone proves nothing. */
public class ProgressEvaluator {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> READ_TOOLS = Set.of("readMultiFile", "readFile", "readTextFile",
      "readTextFileRange", "readPdfFile", "readBinaryFile", "findFile", "listFile", "grepMultiQuery",
      "searchAndRead", "search", "webSearch", "webSearchAndRead", "fetchUrlContent", "searchKnowledge");
  private static final Set<String> WRITE_TOOLS = Set.of("writeMultiFile", "writeTextFile", "writeBinaryFile",
      "applyTextDiff", "deleteFile", "copyFile", "moveFile", "createDirectories");
  private final Path root;
  private final ActionPlan plan;
  private final Set<String> information = new HashSet<>();
  private final Set<String> revisions = new HashSet<>();
  private final Set<String> failures = new HashSet<>();
  private final Set<String> completedSteps = new HashSet<>();

  public ProgressEvaluator(Path root) { this(root, null); }

  public ProgressEvaluator(Path root, ActionPlan plan) {
    this.root = root.toAbsolutePath().normalize();
    this.plan = plan;
    completedSteps.addAll(doneSteps());
  }

  public record Snapshot(Map<Path, String> files, Set<String> completedSteps) {
    public Snapshot { files = Map.copyOf(files); completedSteps = Set.copyOf(completedSteps); }
  }

  public Snapshot beforeTool(String name, String arguments) {
    Map<Path, String> files = new LinkedHashMap<>();
    if (WRITE_TOOLS.contains(name)) collectPaths(parse(arguments), files);
    return new Snapshot(files, doneSteps());
  }

  public List<ProgressEvidence> afterTool(String name, String arguments, String result, Snapshot before) {
    List<ProgressEvidence> evidence = new ArrayList<>();
    before.files().forEach((path, original) -> {
      String current = fingerprint(path);
      if (!original.equals("unavailable") && !current.equals("unavailable") && !original.equals(current)
          && revisions.add(path + ":" + current)) {
        evidence.add(new ProgressEvidence(ProgressEvent.STATE_CHANGED, "File content/existence changed", path.toString()));
      }
    });
    for (String step : doneSteps()) {
      if (!before.completedSteps().contains(step) && completedSteps.add(step)) {
        evidence.add(new ProgressEvidence(ProgressEvent.SUBGOAL_COMPLETED, "Plan step transitioned to DONE", step));
      }
    }
    JsonNode output = parse(result);
    String action = actionKey(name, arguments);
    if (failed(output)) {
      failures.add(action);
    } else if ((successful(output) || (READ_TOOLS.contains(name) && result != null && !result.isBlank()
        && !result.equals("null"))) && failures.remove(action)) {
      evidence.add(new ProgressEvidence(ProgressEvent.ERROR_RESOLVED, "Previously failing action succeeded", name));
    }
    if (READ_TOOLS.contains(name)) {
      // Key by returned information, not arbitrary argument spelling or changing batch order.
      collectInformation(output, result, name, arguments, evidence);
    } else if (name.equals("runCommand") && successful(output)) {
      JsonNode stdout = output.get("stdout");
      if (stdout != null && !stdout.asText().isBlank()) {
        addInformation(name + ":" + action + ":" + canonical(stdout), name, evidence);
      }
    }
    return List.copyOf(evidence);
  }

  public void recordFailure(String name, String arguments) { failures.add(actionKey(name, arguments)); }

  public static String actionKey(String name, String arguments) {
    JsonNode node = parse(arguments);
    return name + ":" + digest(node == null ? Objects.toString(arguments, "") : canonical(node));
  }

  private void collectInformation(JsonNode node, String raw, String name, String args,
      List<ProgressEvidence> evidence) {
    if (node != null && node.isArray()) {
      node.forEach(item -> collectInformation(item, item.toString(), name, args, evidence));
    } else if (!failed(node) && raw != null && !raw.isBlank() && !raw.equals("null")
        && (node == null || !node.isContainerNode() || !node.isEmpty())) {
      JsonNode input = parse(args);
      String source = node != null && node.hasNonNull("path") ? node.get("path").asText()
          : input != null && input.hasNonNull("path") ? input.get("path").asText()
          : input != null && input.hasNonNull("pathStr") ? input.get("pathStr").asText() : name;
      // readMultiFile results may change truncation flags without supplying any new lines.
      if (node != null && node.has("content") && node.get("content").isArray()) {
        int line = node.path("startLine").asInt(1);
        if (line < 1) line = 1;
        for (JsonNode content : node.get("content")) {
          addInformation(source + ":" + line++ + ":" + canonical(content), name, evidence);
        }
      } else {
        addInformation(source + ":" + (node == null ? raw : canonical(node)), name, evidence);
      }
    }
  }

  private void addInformation(String key, String source, List<ProgressEvidence> evidence) {
    if (information.add(digest(key)) && evidence.stream().noneMatch(e -> e.kind() == ProgressEvent.NEW_INFORMATION)) {
      evidence.add(new ProgressEvidence(ProgressEvent.NEW_INFORMATION, "Previously unseen tool information", source));
    }
  }

  private Set<String> doneSteps() {
    if (plan == null) return Set.of();
    Set<String> done = new HashSet<>();
    plan.steps().stream().filter(s -> ActionPlan.STATUS_DONE.equals(s.status()))
        .forEach(s -> done.add(s.id() + ":" + s.description()));
    return done;
  }

  private void collectPaths(JsonNode node, Map<Path, String> files) {
    if (node == null) return;
    if (node.isArray()) { node.forEach(item -> collectPaths(item, files)); return; }
    if (!node.isObject()) return;
    node.fields().forEachRemaining(entry -> {
      if (Set.of("path", "pathStr", "sourcePath", "destPath").contains(entry.getKey()) && entry.getValue().isTextual()) {
        try {
          Path path = root.resolve(entry.getValue().asText()).normalize();
          files.put(path, fingerprint(path));
        } catch (RuntimeException ignored) { /* Tool validates invalid paths. */ }
      } else if (entry.getValue().isContainerNode()) collectPaths(entry.getValue(), files);
    });
  }

  private static String fingerprint(Path path) {
    try {
      if (Files.notExists(path)) return "absent";
      if (Files.isDirectory(path)) return "directory";
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(path)) {
        byte[] buffer = new byte[8192];
        for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (Exception e) { return "unavailable"; }
  }

  private static boolean failed(JsonNode node) {
    if (node == null) return false;
    if (node.isArray()) {
      for (JsonNode child : node) if (failed(child)) return true;
      return false;
    }
    return (node.hasNonNull("error") && !node.path("error").asText().isBlank())
        || (node.has("success") && !node.path("success").asBoolean())
        || (node.hasNonNull("exitCode") && node.path("exitCode").asInt() != 0)
        || node.path("timedOut").asBoolean() || node.path("isError").asBoolean()
        || node.path("status").asText().equals("failed");
  }

  private static boolean successful(JsonNode node) {
    return node != null && !failed(node) && (node.path("success").asBoolean()
        || (node.hasNonNull("exitCode") && node.path("exitCode").asInt(-1) == 0));
  }

  private static JsonNode parse(String value) {
    try { return value == null ? null : JSON.readTree(value); }
    catch (Exception e) { return null; }
  }

  private static String canonical(JsonNode node) {
    if (node.isObject()) {
      Map<String, String> sorted = new TreeMap<>();
      node.fields().forEachRemaining(e -> sorted.put(e.getKey(), canonical(e.getValue())));
      return sorted.toString();
    }
    if (node.isArray()) {
      List<String> items = new ArrayList<>();
      node.forEach(item -> items.add(canonical(item)));
      return items.toString();
    }
    return node.toString();
  }

  private static String digest(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
