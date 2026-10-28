package dev.mikoto2000.rei.tooldelivery;

import dev.mikoto2000.rei.tooldelivery.ReadingMetrics;

import dev.mikoto2000.rei.tooldelivery.ReadingStateStore;

import dev.mikoto2000.rei.tooldelivery.ReadingProblem;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mikoto2000.rei.tooldelivery.SourceEvidence;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;

/** Current prompt presence is rebuilt, never inferred from the acquisition ledger or a summary. */
public final class ReadingDelivery {
  public record Notice(String state, boolean suppressed, List<SourceEvidence> evidence) {
    public Notice { evidence = List.copyOf(evidence); }
  }

  public record Entry(String path, String version, int start, List<String> hashes, String ref) {
    public Entry { hashes = List.copyOf(hashes); }
  }

  public record Saved(List<Entry> entries) {
    public Saved { entries = List.copyOf(entries); }
  }

  private final ReadingStateStore store;
  private final ReadingRawAccess raw;
  @FunctionalInterface
  public interface Resolver { String resolve(ReadingRun owner, String path) throws java.io.IOException; }
  private final Resolver resolver;
  private final Map<String, List<Entry>> available = new LinkedHashMap<>(16, .75f, true);

  public ReadingDelivery(ReadingStateStore store, ReadingRawAccess raw, Resolver resolver) {
    this.store = store;
    this.raw = raw;
    this.resolver = resolver;
  }

  public synchronized void confirm(ReadingRun owner, Prompt prompt) {
    if (owner == null) return;
    String scope = ReadingStateStore.scope(owner);
    List<Entry> current = collect(owner, prompt, true);
    available.put(scope, current);
    while (available.size() > 64 || available.values().stream().flatMap(List::stream)
        .mapToLong(entry -> entry.hashes().size() * 128L + 512L).sum() > 8L * 1024 * 1024)
      available.remove(available.keySet().iterator().next());
    var history = new ArrayList<>(history(owner));
    for (var entry : current) if (!history.contains(entry)) history.add(entry);
    if (history.size() > 128)
      history = new ArrayList<>(history.subList(history.size() - 128, history.size()));
    try {
      int retained = ReadingJson.JSON.writeValueAsBytes(new Saved(List.of())).length;
      int start = history.size();
      for (int index = history.size() - 1; index >= 0; index--) {
        int cost = ReadingJson.JSON.writeValueAsBytes(history.get(index)).length + (start < history.size() ? 1 : 0);
        if (cost > 2 * 1024 * 1024 - retained) break;
        retained += cost;
        start = index;
      }
      store.save(owner, "delivery", "evidence", new Saved(history.subList(start, history.size())), Duration.ofDays(7));
    } catch (java.io.IOException | RuntimeException unavailable) {
      ReadingMetrics.add(owner, "deliveryPersistenceUnavailable", 1);
    }
    ReadingMetrics.flush(owner, store);
  }

  /**
   * Recheck after projection: a previously present source may have disappeared during compression.
   */
  public Prompt restoreSuppressed(ReadingRun owner, Prompt prompt) {
    if (owner == null) return prompt;
    var present = collect(owner, prompt, false);
    var messages = new ArrayList<org.springframework.ai.chat.messages.Message>();
    for (var message : prompt.getInstructions()) {
      if (!(message instanceof ToolResponseMessage tool)) {
        messages.add(message);
        continue;
      }
      var responses = new ArrayList<ToolResponseMessage.ToolResponse>();
      for (var response : tool.getResponses()) {
        if (!response.name().equals("readMultiFile")) {
          responses.add(response);
          continue;
        }
        try {
          var tree = ReadingJson.JSON.readTree(response.responseData());
          boolean changed = false;
          if (tree != null && tree.isArray())
            for (var node : tree) {
              if (!node.path("delivery").path("suppressed").asBoolean()) continue;
              changed = true;
              var file = (com.fasterxml.jackson.databind.node.ObjectNode) node;
              try {
                String path = relative(owner, file.path("path").asText());
                var rows =
                    recover(
                        owner,
                        path,
                        file.path("version").asText(),
                        file.path("startLine").asInt(),
                        file.path("endLine").asInt());
                var candidate = ReadingJson.JSON.createObjectNode().put("path", path)
                    .put("startLine", file.path("startLine").asInt()).put("version", file.path("version").asText());
                candidate.set("content", ReadingJson.JSON.valueToTree(rows));
                if (covers(present, path, candidate)) continue;
                file.set("content", ReadingJson.JSON.valueToTree(rows));
                file.put(
                    "returnedBytes",
                    rows.stream()
                        .mapToInt(row -> row.getBytes(StandardCharsets.UTF_8).length + 1)
                        .sum());
                ((com.fasterxml.jackson.databind.node.ObjectNode) file.path("delivery"))
                    .put("suppressed", false)
                    .put("state", "RESTORABLE");
                ReadingMetrics.add(owner, "restoredBeforeDelivery", 1);
              } catch (java.io.IOException | RuntimeException unavailable) {
                file.put("error", "SOURCE_RESTORATION_UNAVAILABLE");
                file.set(
                    "readingProblem",
                    ReadingJson.JSON.valueToTree(
                        new ReadingProblem(
                            "SOURCE_RESTORATION_UNAVAILABLE",
                            "Saved source could not be restored",
                            "Read the explicit file range again.")));
                ((com.fasterxml.jackson.databind.node.ObjectNode) file.path("delivery"))
                    .put("suppressed", false)
                    .put("state", "UNAVAILABLE");
              }
            }
          responses.add(
              changed
                  ? new ToolResponseMessage.ToolResponse(
                      response.id(), response.name(), ReadingJson.JSON.writeValueAsString(tree))
                  : response);
        } catch (java.io.IOException malformed) {
          responses.add(response);
        }
      }
      messages.add(
          ToolResponseMessage.builder().responses(responses).metadata(tool.getMetadata()).build());
    }
    return new Prompt(messages, prompt.getOptions());
  }

  private List<String> recover(
      ReadingRun owner, String path, String version, int start, int end)
      throws java.io.IOException {
    if (start < 1 || end < start || (long) end - start > 5000)
      throw ReadingProblem.fail("SOURCE_RESTORATION_UNAVAILABLE");
    var rows = new java.util.TreeMap<Integer, String>();
    var candidates = new ArrayList<>(history(owner));
    for (var entry : current(owner)) if (!candidates.contains(entry)) candidates.add(entry);
    for (var entry : candidates) {
      if (!entry.path().equals(path) || !entry.version().equals(version)) continue;
      if (entry.start() > end || (long) entry.start() + entry.hashes().size() <= start) continue;
      String source;
      try { source = raw.read(owner.conversationId(), entry.ref()); }
      catch (RuntimeException unavailable) { continue; }
      var tree = ReadingJson.JSON.readTree(source);
      if (tree != null && tree.isObject() && tree.path("files").isArray())
        tree = tree.path("files");
      if (tree == null || !tree.isArray()) continue;
      for (var file : tree) {
        if (!file.path("version").asText().equals(version)
            || !relative(owner, file.path("path").asText()).equals(path)) continue;
        var sections =
            file.path("sections").isArray()
                ? file.path("sections")
                : ReadingJson.JSON.valueToTree(List.of(file));
        for (var section : sections) {
          int line = section.path("startLine").asInt();
          for (var text : section.path("content")) {
            int index = line - entry.start();
            if (line >= start
                && line <= end
                && index >= 0
                && index < entry.hashes().size()
                && hash(text.asText()).equals(entry.hashes().get(index)))
              rows.put(line, text.asText());
            line++;
          }
        }
      }
    }
    var result = new ArrayList<String>();
    for (int line = start; line <= end; line++) {
      if (!rows.containsKey(line)) throw ReadingProblem.fail("SOURCE_RESTORATION_UNAVAILABLE");
      result.add(rows.get(line));
    }
    return List.copyOf(result);
  }

  public Notice inspect(ReadingRun owner, JsonNode result, boolean exact, boolean suppressRequested) {
    if (owner == null || result.hasNonNull("error") || result.path("content").isEmpty()) return null;
    String path;
    try { path = relative(owner, result.path("path").asText()); }
    catch (java.io.IOException error) { return null; }
    String version = result.path("version").asText();
    var previous = history(owner);
    var present = current(owner);
    String state = exact && covers(present, path, result) ? "AVAILABLE"
        : exact && covers(previous, path, result) && restorable(owner, previous, path, version) ? "RESTORABLE"
        : previous.stream().anyMatch(e -> e.path().equals(path) && !e.version().equals(version)) ? "STALE" : "UNAVAILABLE";
    boolean suppress = suppressRequested && exact && state.equals("AVAILABLE");
    var evidence = previous.stream().filter(e -> e.path().equals(path) && e.version().equals(version))
        .map(e -> new SourceEvidence(e.path(), e.version(), e.start(), e.start() + e.hashes().size() - 1, null, "file-read", e.ref())).limit(8).toList();
    if (suppress) ReadingMetrics.add("suppressedBodyBytes", result.path("returnedBytes").asInt());
    return new Notice(state, suppress, evidence);
  }

  private synchronized List<Entry> current(ReadingRun owner) {
    return available.getOrDefault(ReadingStateStore.scope(owner), List.of());
  }

  private List<Entry> history(ReadingRun owner) {
    try {
      return store.load(owner, "delivery", "evidence", Saved.class).entries();
    } catch (RuntimeException unavailable) {
      return List.of();
    }
  }

  private boolean restorable(
      ReadingRun owner, List<Entry> entries, String path, String version) {
    for (var entry : entries)
      if (entry.path().equals(path) && entry.version().equals(version)) {
        try {
          raw.read(owner.conversationId(), entry.ref());
          return true;
        } catch (RuntimeException unavailable) {
          /* Missing raw bodies never establish availability. */
        }
      }
    return false;
  }

  private static boolean covers(List<Entry> entries, String path, JsonNode result) {
    if (!result.hasNonNull("startLine") || !result.hasNonNull("version")) return false;
    for (int index = 0; index < result.path("content").size(); index++) {
      int line = result.path("startLine").asInt() + index;
      String hash = hash(result.path("content").get(index).asText());
      if (entries.stream().noneMatch(e -> e.path().equals(path) && e.version().equals(result.path("version").asText())
          && line >= e.start() && line - e.start() < e.hashes().size() && e.hashes().get(line - e.start()).equals(hash))) return false;
    }
    return true;
  }

  private List<Entry> collect(ReadingRun owner, Prompt prompt, boolean preserve) {
    var entries = new ArrayList<Entry>();
    var calls = new java.util.HashMap<String, JsonNode>();
    for (var message : prompt.getInstructions()) {
      if (message instanceof AssistantMessage assistant)
        for (var call : assistant.getToolCalls()) {
          try {
            calls.put(call.id(), ReadingJson.JSON.readTree(call.arguments()));
          } catch (java.io.IOException malformed) {
            /* Unknown arguments are not proof of complete rows. */
          }
        }
      if (!(message instanceof ToolResponseMessage tool)) continue;
      for (var response : tool.getResponses()) {
        if (response.name().equals("readRawToolResult")) {
          try {
            var page =
                raw.parse(response.responseData());
            if (page.offset() == 0
                && !page.hasMore()
                && raw.read(owner.conversationId(), page.rawResultRef()).equals(page.content()))
              entries.addAll(
                  history(owner).stream()
                      .filter(e -> e.ref().equals(page.rawResultRef()))
                      .toList());
          } catch (RuntimeException unavailable) {
            /* Partial or unavailable originals cannot suppress source. */
          }
          continue;
        }
        if (!List.of("readMultiFile", "searchAndRead", "searchFilePage").contains(response.name()))
          continue;
        try {
          var tree = ReadingJson.JSON.readTree(response.responseData());
          // Lossless result pages are not the original source JSON, so no presence is inferred.
          if (tree == null || tree.has("rawResultRef")) continue;
          String ref =
              preserve
                  ? raw.save(
                      owner.conversationId(),
                      owner.runId(),
                      response.name(),
                      response.id(),
                      response.responseData())
                  : "";
          if (tree.isObject() && tree.path("files").isArray()) tree = tree.path("files");
          if (!tree.isArray()) continue;
          for (int index = 0; index < tree.size(); index++) {
            var file = tree.get(index);
            if (file.hasNonNull("error")
                || file.hasNonNull("symbol")
                || !file.hasNonNull("version")
                || !file.hasNonNull("path")) continue;
            String path = relative(owner, file.path("path").asText());
            JsonNode arguments = calls.get(response.id());
            int offset = 0;
            if (response.name().equals("readMultiFile")) {
              if (arguments == null
                  || !arguments.path("files").isArray()
                  || index >= arguments.path("files").size()) continue;
              var request = arguments.path("files").get(index);
              if (!relative(owner, request.path("path").asText()).equals(path)) continue;
              offset = request.path("offset").asInt(0);
            }
            if (file.path("sections").isArray())
              for (var section : file.path("sections"))
                add(entries, path, file.path("version").asText(), section, file, 0, ref);
            else add(entries, path, file.path("version").asText(), file, file, offset, ref);
          }
        } catch (java.io.IOException | RuntimeException unavailable) {
          /* Fail open to returning source. */
        }
      }
    }
    return List.copyOf(
        entries.size() > 128 ? entries.subList(entries.size() - 128, entries.size()) : entries);
  }

  private static void add(
      List<Entry> entries,
      String path,
      String version,
      JsonNode section,
      JsonNode file,
      int offset,
      String ref) {
    if (!section.path("content").isArray() || section.path("startLine").asInt(0) < 1) return;
    int start = section.path("startLine").asInt();
    var hashes = new ArrayList<String>();
    for (var line : section.path("content")) {
      if (!line.isTextual()) return;
      hashes.add(hash(line.asText()));
    }
    if (offset > 0 && !hashes.isEmpty()) {
      hashes.removeFirst();
      start++;
    }
    if ((file.path("nextRead").path("offset").asInt(0) > 0
            || section.path("truncated").asBoolean() && !file.path("nextRead").isObject())
        && !hashes.isEmpty()) hashes.removeLast();
    if (!hashes.isEmpty()) entries.add(new Entry(path, version, start, List.copyOf(hashes), ref));
  }

  private String relative(ReadingRun owner, String path) throws java.io.IOException {
    return resolver.resolve(owner, path);
  }

  private static String hash(String text) {
    return ReadingJson.hash(text.getBytes(StandardCharsets.UTF_8));
  }
}
