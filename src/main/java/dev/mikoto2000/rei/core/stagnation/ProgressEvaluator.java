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
  private final InformationLedger information = new InformationLedger();
  public InformationLedger.State informationSnapshot() { return information.snapshot(); }
  public void restoreInformation(InformationLedger.State state) { information.restore(state); }
  /** Called after the Run's plan has been initialized/restored, before any tool executes. */
  public synchronized void capturePlanBaseline() { completedSteps.clear(); completedSteps.addAll(doneSteps()); }
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

  public synchronized List<ProgressEvidence> afterTool(String name, String arguments, String result, Snapshot before) {
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
    if(name.equals("readReviewFile") && output!=null && output.has("range")) {
      var range=output.path("range");var span=range.path("span");
      var observed=information.observeRange("review-chars:"+range.path("path").asText(),range.path("version").asText(),span.path("start").asInt()+1,span.path("end").asInt()+1);
      if(observed.status()==InformationLedger.Status.NEW)evidence.add(new ProgressEvidence(ProgressEvent.NEW_INFORMATION,"New review characters fetched",range.path("path").asText(),range.path("version").asText()));
      return List.copyOf(evidence);
    }
    if(name.equals("submitReviewResult") && output!=null && output.hasNonNull("id")) {
      for(var entry:output.path("ranges"))if(entry.path("reviewed").asBoolean()&&entry.path("delivered").asBoolean()&&!entry.path("stale").asBoolean()) {
        var range=entry.path("range");var span=range.path("span");
        var observed=information.observeRange("reviewed:"+output.path("id").asText()+":"+range.path("path").asText(),range.path("version").asText(),span.path("start").asInt()+1,span.path("end").asInt()+1);
        if(observed.status()==InformationLedger.Status.NEW)evidence.add(new ProgressEvidence(ProgressEvent.STATE_CHANGED,"New review range verified",range.path("path").asText(),range.path("version").asText()));
      }
      for(var finding:output.path("findings"))if(information.observe(digest(canonical(finding))).equals(InformationLedger.Status.NEW))
        evidence.add(new ProgressEvidence(ProgressEvent.STATE_CHANGED,"Verified review finding added",finding.path("path").asText(),finding.path("version").asText()));
      return List.copyOf(evidence);
    }
    if(name.equals("readRawToolResult")) {
      try {
        var header=result.split("\\n",8);
        if(header.length!=8)throw new IllegalArgumentException();
        String ref=header[2].substring(14);java.util.UUID.fromString(ref);
        int start=Integer.parseInt(header[3].substring(8)),end=Integer.parseInt(header[5].substring(12));
        if(end-start!=header[7].length())throw new IllegalArgumentException();
        var observed=information.observeRange("raw:"+ref,ref,start+1,end+1);
        if(observed.status()==InformationLedger.Status.NEW)evidence.add(new ProgressEvidence(ProgressEvent.NEW_INFORMATION,"New raw result characters",ref));
      }catch(RuntimeException invalid){unknown(name,"RAW_RANGE_UNAVAILABLE",evidence);}
      return List.copyOf(evidence);
    }
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

  public synchronized void recordFailure(String name, String arguments) { remember(failures, actionKey(name, arguments)); }
  public static boolean failedResult(String result) { return failed(parse(result)); }

  public static String actionKey(String name, String arguments) {
    JsonNode node = parse(arguments);
    return name + ":" + digest(node == null ? Objects.toString(arguments, "") : canonical(node));
  }

  private void collectInformation(JsonNode node, String raw, String name, String args,
      List<ProgressEvidence> evidence) {
    if (node != null && node.isArray()) {
      JsonNode input = parse(args);
      JsonNode requests = input == null ? null : input.get("files");
      for (int index = 0; index < node.size(); index++) {
        JsonNode item = node.get(index);
        String itemArguments = args;
        if (name.equals("readMultiFile") && requests != null && requests.isArray() && requests.size() == node.size()) {
          JsonNode request = requests.get(index);
          try {
            // The built-in reader returns one result per request, in request order.
            if (request.hasNonNull("path") && item.hasNonNull("path")
                && normalizeSource(request.path("path").asText()).equals(normalizeSource(item.path("path").asText()))
                && request.path("startLine").asInt(1) == item.path("startLine").asInt(0)) itemArguments = request.toString();
          } catch (RuntimeException invalid) { /* Unknown association is handled conservatively below. */ }
        }
        collectInformation(item, item.toString(), name, itemArguments, evidence);
      }
    } else if (node != null && !failed(node) && name.equals("grepMultiQuery") && node.has("matches")) {
      for (JsonNode match : node.path("matches")) {
        if (!match.hasNonNull("path") || !match.hasNonNull("content")) continue;
        try {
          String source = normalizeSource(match.path("path").asText());
          addInformation("grep:" + source + ":" + match.path("line").asInt() + ":" + match.path("content").asText(), source, evidence);
        } catch (RuntimeException invalid) { /* Tool validates paths. */ }
      }
    } else if (node == null && Set.of("readMultiFile", "searchAndRead", "grepMultiQuery").contains(name)) {
      unknown(name, "MALFORMED_RESULT", evidence);
    } else if (!failed(node) && raw != null && !raw.isBlank() && !raw.equals("null")
        && (node == null || !node.isContainerNode() || !node.isEmpty())) {
      JsonNode input = parse(args);
      String source = node != null && node.hasNonNull("path") ? node.get("path").asText()
          : input != null && input.hasNonNull("path") ? input.get("path").asText()
          : input != null && input.hasNonNull("pathStr") ? input.get("pathStr").asText() : name;
      boolean fileSource = node != null && node.hasNonNull("path") || input != null && (input.hasNonNull("path") || input.hasNonNull("pathStr"));
      if (fileSource) {
        try { source = normalizeSource(source); }
        catch (RuntimeException invalid) { return; }
      }
      if (fileSource && node != null && node.isObject() && node.hasNonNull("version")) {
        if (node.hasNonNull("symbol")) {
          // Symbol displays may omit lines: never infer a contiguous file range.
          addInformation(source + ":" + node.path("version").asText() + ":" + canonical(node.path("content")), source, evidence);
        } else if (node.has("sections")) {
          for (JsonNode section : node.path("sections")) observeRange(source, node.path("version").asText(), section, node, input, evidence);
        } else if (node.path("content").isArray()) {
          observeRange(source, node.path("version").asText(), node, node, input, evidence);
        } else unknown(source, "FILE_RANGE_UNAVAILABLE", evidence);
        return;
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
  private void observeRange(String source, String version, JsonNode section, JsonNode output, JsonNode input,
      List<ProgressEvidence> evidence) {
    int start = section.path("startLine").asInt(0);
    int originalStart = start;
    int count = section.path("content").size();
    if (count == 0) return;
    if (start < 1 || (long) start + count > Integer.MAX_VALUE) { unknown(source, "FILE_RANGE_UNAVAILABLE", evidence); return; }
    boolean firstFragment = false;
    int fragmentOffset = 0;
    boolean ambiguousOffset = false;
    if (input != null) {
      fragmentOffset = Math.max(0, input.path("offset").asInt(0));
      firstFragment = fragmentOffset > 0;
      Set<Integer> offsets = new HashSet<>();
      for (JsonNode request : input.path("files")) {
        if (request.hasNonNull("path") && request.path("startLine").asInt(1) == originalStart) {
          try {
            if (normalizeSource(request.path("path").asText()).equals(source)) {
              int offset = Math.max(0, request.path("offset").asInt(0));
              offsets.add(offset);
              if (offset > 0) { firstFragment = true; fragmentOffset = offset; }
            }
          }
          catch (RuntimeException ignored) { /* Invalid paths remain unknown to the tool. */ }
        }
      }
      ambiguousOffset = offsets.size() > 1;
    }
    int originalCount = count;
    // A byte-limited result can end in a fragment of its last line. Retain only complete lines.
    JsonNode next = output.path("nextRead");
    if (next.path("offset").asInt(0) > 0 && next.path("startLine").asInt(0) == start + count - 1) count--;
    else if (section.path("truncated").asBoolean() && !next.isObject()) count--;
    if (firstFragment && count > 0) { start++; count--; }
    if (count > 0) {
      var observed = information.observeRange(source, version, start, start + count);
      if (observed.status() == InformationLedger.Status.NEW) {
        evidence.add(new ProgressEvidence(ProgressEvent.NEW_INFORMATION,
            "type=FILE_RANGE newLines=" + observed.newLines(), source, version));
      } else if (observed.status() == InformationLedger.Status.UNKNOWN) unknown(source, "LEDGER_CAPACITY_OR_HISTORY", evidence);
    }
    if (count < originalCount && !information.covers(source, version, originalStart, originalStart + originalCount)) {
      // Deduplicate observed fragments, without claiming that their whole line was read.
      if (ambiguousOffset) unknown(source, "FILE_RANGE_UNAVAILABLE", evidence);
      else addInformation(source + ":" + version + ":fragment:" + originalStart + ":offset:" + fragmentOffset
          + ":" + canonical(section.path("content")), source, evidence);
    }
  }
  private static void unknown(String source, String reason, List<ProgressEvidence> evidence) {
    if (evidence.stream().noneMatch(e -> e.kind() == ProgressEvent.INFORMATION_UNKNOWN))
      evidence.add(new ProgressEvidence(ProgressEvent.INFORMATION_UNKNOWN, reason, source));
  }
  private String normalizeSource(String source) {
    String path = root.resolve(source).normalize().toString();
    return root.getFileSystem().getSeparator().equals("\\") ? path.toLowerCase(Locale.ROOT) : path;
  }
  private void addInformation(String key, String source, List<ProgressEvidence> evidence) {
    addInformation(key, source, digest(key), evidence);
  }
  private void addInformation(String key, String source, String revision, List<ProgressEvidence> evidence) {
    var status = information.observe(digest(key));
    if (status == InformationLedger.Status.NEW && evidence.stream().noneMatch(e -> e.kind() == ProgressEvent.NEW_INFORMATION)) {
      evidence.add(new ProgressEvidence(ProgressEvent.NEW_INFORMATION, "Previously unseen tool information", source, revision));
    } else if (status == InformationLedger.Status.UNKNOWN) unknown(source, "LEDGER_CAPACITY_OR_HISTORY", evidence);
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
    if (name.equals("webSearch")) { collectDiscoveries(output, items); return items; }
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
  private static void collectDiscoveries(JsonNode node, List<Information> items) {
    if (node == null) return;
    if (node.isArray()) { node.forEach(item -> collectDiscoveries(item, items)); return; }
    if (!node.isObject() || failed(node)) return;
    String url = node.path("url").asText();
    if (!url.isBlank()) {
      String key = "web-discovery:" + digest(url) + ":" + node.path("snippet").asText();
      items.add(new Information(key, "web-source:" + digest(url), digest(key)));
    }
    collectDiscoveries(node.get("results"), items);
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
