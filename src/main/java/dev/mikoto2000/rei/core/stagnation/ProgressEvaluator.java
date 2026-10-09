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
  private static final Set<String> WEB_TOOLS = Set.of("webSearch", "webSearchAndRead", "fetchUrlContent", "searchKnowledge");
  private static final int LEDGER_LIMIT = 1024;
  private final dev.mikoto2000.rei.goal.FileGoalVerifier verifier = new dev.mikoto2000.rei.goal.FileGoalVerifier();
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
          && remember(revisions, path + ":" + current)) {
        evidence.add(new ProgressEvidence(ProgressEvent.STATE_CHANGED, "File content/existence changed", path.toString(), current));
      }
    });
    for (String step : doneSteps()) {
      if (!before.completedSteps().contains(step) && completedSteps.add(step)) {
        evidence.add(new ProgressEvidence(ProgressEvent.SUBGOAL_COMPLETED, "Plan step transitioned to DONE", step));
      }
    }
    JsonNode output = parse(result);
    if (name.equals("applyTextChangeSet")) observeChangeSet(output, evidence);
    var webInformation = WEB_TOOLS.contains(name) ? webInformation(name, output, result) : List.<Information>of();
    String action = actionKey(name, arguments);
    if (failed(output)) {
      remember(failures, action);
    } else if ((!WEB_TOOLS.contains(name) || !webInformation.isEmpty()) && (successful(output) || (READ_TOOLS.contains(name) && result != null && !result.isBlank()
        && !result.equals("null"))) && failures.remove(action)) {
      evidence.add(new ProgressEvidence(ProgressEvent.ERROR_RESOLVED, "Previously failing action succeeded", name));
    }
    if (WEB_TOOLS.contains(name)) {
      for (var item : webInformation) addInformation(item.key(), item.source(), item.revision(), evidence);
    } else if (READ_TOOLS.contains(name)) {
      // Key by returned information, not arbitrary argument spelling or changing batch order.
      collectInformation(output, result, name, arguments, evidence);
    } else if (name.equals("runCommand") && successful(output)) {
      JsonNode stdout = output.get("stdout");
      if (stdout != null && !stdout.asText().isBlank()) {
        addInformation("command-output:" + canonical(stdout), name, evidence);
      }
    }
    return List.copyOf(evidence);
  }

  public void recordFailure(String name, String arguments) { remember(failures, actionKey(name, arguments)); }

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
      boolean fileSource = node != null && node.hasNonNull("path") || input != null && (input.hasNonNull("path") || input.hasNonNull("pathStr"));
      if (fileSource) {
        try { source = root.resolve(source).normalize().toString(); }
        catch (RuntimeException invalid) { return; }
      }
      // Share line identities across file readers; metadata and argument spelling do not add facts.
      int line = Math.max(1, node != null ? node.path("startLine").asInt(1) : input == null ? 1 : input.path("startLine").asInt(1));
      if (node != null && node.has("content") && node.get("content").isArray()) {
        for (JsonNode content : node.get("content"))
          addInformation(source + ":" + line++ + ":" + canonical(content), source, evidence);
      } else if (fileSource && (node == null || node.isValueNode() || node.has("content") && node.get("content").isTextual())) {
        String text = node == null ? raw : node.isValueNode() ? node.asText() : node.get("content").asText();
        for (String content : text.lines().toList())
          addInformation(source + ":" + line++ + ":" + canonical(JSON.valueToTree(content)), source, evidence);
      } else {
        addInformation(source + ":" + (node == null ? raw : canonical(node)), source, evidence);
      }
    }
  }
  private void addInformation(String key, String source, List<ProgressEvidence> evidence) {
    addInformation(key, source, digest(key), evidence);
  }
  private void addInformation(String key, String source, String revision, List<ProgressEvidence> evidence) {
    if (remember(information, digest(key)) && evidence.stream().noneMatch(e -> e.kind() == ProgressEvent.NEW_INFORMATION)) {
      evidence.add(new ProgressEvidence(ProgressEvent.NEW_INFORMATION, "Previously unseen tool information", source, revision));
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
    node.properties().forEach(entry -> {
      if (Set.of("path", "pathStr", "sourcePath", "destPath").contains(entry.getKey()) && entry.getValue().isTextual()) {
        try {
          Path path = root.resolve(entry.getValue().asText()).normalize();
          files.put(path, fingerprint(path));
        } catch (RuntimeException ignored) { /* Tool validates invalid paths. */ }
      } else if (entry.getValue().isContainerNode()) collectPaths(entry.getValue(), files);
    });
  }

  private String fingerprint(Path path) {
    if (!path.startsWith(root)) return "unavailable";
    String relative = root.relativize(path).toString();
    var observed = verifier.fingerprint(root, relative);
    if (observed.available()) return observed.sha256();
    if (observed.reason().equals("file_missing_or_not_regular")) {
      if (Files.notExists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "absent";
      if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return "directory";
    }
    return "unavailable";
  }

  private static boolean remember(Set<String> ledger, String key) {
    // Never evict old evidence: eviction could make an old read appear new again.
    return ledger.size() < LEDGER_LIMIT && ledger.add(key);
  }

  private void observeChangeSet(JsonNode node, List<ProgressEvidence> evidence) {
    if (node == null || !node.path("status").asText().equals("APPLIED")) return;
    String proposed = node.path("proposedSha256").asText();
    if (!proposed.matches("[a-f0-9]{64}") || proposed.equals(node.path("baselineSha256").asText())
        || !proposed.equals(node.path("currentSha256").asText())) return;
    try {
      Path path = root.resolve(node.path("path").asText()).normalize();
      if (proposed.equals(fingerprint(path)) && remember(revisions, path + ":" + proposed))
        evidence.add(new ProgressEvidence(ProgressEvent.STATE_CHANGED, "Applied Change Set revision observed", path.toString(), proposed));
    } catch (RuntimeException invalid) { /* Unknown revision does not prove progress. */ }
  }

  private record Information(String key,String source,String revision) {}
  private static List<Information> webInformation(String name,JsonNode output,String raw) {
    var items = new ArrayList<Information>();
    if (name.equals("webSearch")) return items; // URL discovery and metadata stay in Web metrics.
    if (!name.equals("searchKnowledge")) { collectWeb(output, items); return items; }
    String result = output != null && output.isTextual() ? output.asText() : raw;
    if (result == null) return items;
    for (String line : result.split("\\R")) {
      if (line.startsWith("Web external_untrusted sourceType=")) {
        int json = line.indexOf(": {");
        if (json >= 0) collectWeb(parse(line.substring(json + 2)), items);
      } else if (line.startsWith("Vector: ")) {
        int snippet = line.indexOf(" | snippet=");
        if (snippet >= 0 && !line.substring(snippet + 11).isBlank()) {
          String content = line.substring(snippet + 11);
          items.add(new Information("vector-body:" + content, "indexed-content", digest(content)));
        }
      }
    }
    return items;
  }
  private static void collectWeb(JsonNode node,List<Information> items) {
    if (node == null) return;
    if (node.isArray()) { node.forEach(item -> collectWeb(item, items)); return; }
    if (!node.isObject() || failed(node) || node.hasNonNull("errorType")) return;
    JsonNode content = node.get("content");
    if (content != null && content.isTextual() && !content.asText().isBlank()) {
      String url = node.path("url").asText(node.path("finalUrl").asText("unknown"));
      items.add(new Information("web-body:" + content.asText(), "web-source:" + digest(url), digest(content.asText())));
    }
    for (String field : List.of("results", "primaryResults", "secondaryResults", "webContext"))
      collectWeb(node.get(field), items);
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
      node.properties().forEach(e -> sorted.put(e.getKey(), canonical(e.getValue())));
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
