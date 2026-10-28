package dev.mikoto2000.rei.core;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.tooldelivery.SourceEvidence;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** Current prompt presence is rebuilt, never inferred from the acquisition ledger or a summary. */
@Component
public final class ReadingDelivery {
  public record Notice(String state, boolean suppressed, List<SourceEvidence> evidence) {}
  public record Entry(String path, String version, int start, List<String> hashes, String ref) {}
  public record Saved(List<Entry> entries) {}
  private final ReadingStateStore store;
  private final RawToolResultStore raw;
  private final Map<String, List<Entry>> available = new LinkedHashMap<>();
  public ReadingDelivery(ReadingStateStore store, RawToolResultStore raw) { this.store = store; this.raw = raw; }

  public synchronized void confirm(AgentRunContext owner, Prompt prompt) {
    String scope = ReadingStateStore.scope(owner);
    List<Entry> current = collect(owner, prompt, true);
    available.put(scope, current);
    while (available.size() > 256) available.remove(available.keySet().iterator().next());
    var history = new ArrayList<>(history(owner));
    for (var entry : current) if (!history.contains(entry)) history.add(entry);
    if (history.size() > 128) history = new ArrayList<>(history.subList(history.size() - 128, history.size()));
    store.save(owner, "delivery", "evidence", new Saved(List.copyOf(history)), Duration.ofDays(7));
  }

  Tools.ReadFileResult apply(AgentRunContext owner, Tools.ReadFileRequest request, Tools.ReadFileResult result) {
    if (owner == null || result.error() != null || result.content().isEmpty()) return result;
    String path;
    try { path = relative(owner, result.path()); }
    catch (java.io.IOException error) { return result; }
    var previous = history(owner);
    var present = current(owner);
    boolean exact = result.symbol() == null && !result.truncated() && (request.offset() == null || request.offset() == 0);
    String state = exact && covers(present, path, result) ? "AVAILABLE"
        : exact && covers(previous, path, result) && restorable(owner, previous, path, result.version()) ? "RESTORABLE"
        : previous.stream().anyMatch(e -> e.path().equals(path) && !e.version().equals(result.version())) ? "STALE" : "UNAVAILABLE";
    boolean suppress = Boolean.TRUE.equals(request.unreadOnly()) && request.startLine() != null
        && request.endLine() != null && exact && state.equals("AVAILABLE");
    var evidence = previous.stream().filter(e -> e.path().equals(path) && e.version().equals(result.version()))
        .map(e -> new SourceEvidence(e.path(), e.version(), e.start(), e.start() + e.hashes().size() - 1,
            null, "file-read", e.ref())).limit(8).toList();
    if (suppress) ReadingMetrics.add("suppressedBodyBytes", result.returnedBytes());
    return new Tools.ReadFileResult(result.path(), result.startLine(), result.endLine(), suppress ? List.of() : result.content(),
        result.truncated(), result.error(), result.version(), result.nextRead(), suppress ? 0 : result.returnedBytes(),
        result.symbol(), result.candidates(), result.ownerOverview(), result.truncationReason(), new Notice(state, suppress, evidence));
  }

  private synchronized List<Entry> current(AgentRunContext owner) {
    return available.getOrDefault(ReadingStateStore.scope(owner), List.of());
  }
  private List<Entry> history(AgentRunContext owner) {
    try { return store.load(owner, "delivery", "evidence", Saved.class).entries(); }
    catch (RuntimeException unavailable) { return List.of(); }
  }
  private boolean restorable(AgentRunContext owner, List<Entry> entries, String path, String version) {
    for (var entry : entries) if (entry.path().equals(path) && entry.version().equals(version)) {
      try { raw.read(owner.conversationId(), entry.ref()); return true; }
      catch (RuntimeException unavailable) { /* Missing raw bodies never establish availability. */ }
    }
    return false;
  }
  private static boolean covers(List<Entry> entries, String path, Tools.ReadFileResult result) {
    if (result.startLine() == null || result.version() == null) return false;
    for (int index = 0; index < result.content().size(); index++) {
      int line = result.startLine() + index;
      String hash = hash(result.content().get(index));
      if (entries.stream().noneMatch(e -> e.path().equals(path) && e.version().equals(result.version())
          && line >= e.start() && line - e.start() < e.hashes().size() && e.hashes().get(line - e.start()).equals(hash))) return false;
    }
    return true;
  }
  private List<Entry> collect(AgentRunContext owner, Prompt prompt, boolean preserve) {
    var entries = new ArrayList<Entry>();
    var calls = new java.util.HashMap<String, JsonNode>();
    for (var message : prompt.getInstructions()) {
      if (message instanceof AssistantMessage assistant) for (var call : assistant.getToolCalls()) {
        try { calls.put(call.id(), StorageDatabase.JSON.readTree(call.arguments())); }
        catch (java.io.IOException malformed) { /* Unknown arguments are not proof of complete rows. */ }
      }
      if (!(message instanceof ToolResponseMessage tool)) continue;
      for (var response : tool.getResponses()) {
        if (!List.of("readMultiFile", "searchAndRead", "searchFilePage").contains(response.name())) continue;
        try {
          var tree = StorageDatabase.JSON.readTree(response.responseData());
          // Lossless result pages are not the original source JSON, so no presence is inferred.
          if (tree == null || tree.has("rawResultRef")) continue;
          String ref = preserve ? raw.save(owner.conversationId(), owner.runId(), response.name(), response.id(), response.responseData()) : "";
          if (tree.isObject() && tree.path("files").isArray()) tree = tree.path("files");
          if (!tree.isArray()) continue;
          for (int index = 0; index < tree.size(); index++) {
            var file = tree.get(index);
            if (file.hasNonNull("error") || file.hasNonNull("symbol") || !file.hasNonNull("version") || !file.hasNonNull("path")) continue;
            String path = relative(owner, file.path("path").asText());
            JsonNode arguments = calls.get(response.id());
            int offset = 0;
            if (response.name().equals("readMultiFile")) {
              if (arguments == null || !arguments.path("files").isArray() || index >= arguments.path("files").size()) continue;
              var request = arguments.path("files").get(index);
              if (!relative(owner, request.path("path").asText()).equals(path)) continue;
              offset = request.path("offset").asInt(0);
            }
            if (file.path("sections").isArray()) for (var section : file.path("sections"))
              add(entries, path, file.path("version").asText(), section, file, 0, ref);
            else add(entries, path, file.path("version").asText(), file, file, offset, ref);
          }
        } catch (java.io.IOException | RuntimeException unavailable) { /* Fail open to returning source. */ }
      }
    }
    return List.copyOf(entries.size() > 128 ? entries.subList(entries.size() - 128, entries.size()) : entries);
  }
  private static void add(List<Entry> entries, String path, String version, JsonNode section, JsonNode file, int offset, String ref) {
    if (!section.path("content").isArray() || section.path("startLine").asInt(0) < 1) return;
    int start = section.path("startLine").asInt();
    var hashes = new ArrayList<String>();
    for (var line : section.path("content")) {
      if (!line.isTextual()) return;
      hashes.add(hash(line.asText()));
    }
    if (offset > 0 && !hashes.isEmpty()) { hashes.removeFirst(); start++; }
    if ((file.path("nextRead").path("offset").asInt(0) > 0
        || section.path("truncated").asBoolean() && !file.path("nextRead").isObject()) && !hashes.isEmpty()) hashes.removeLast();
    if (!hashes.isEmpty()) entries.add(new Entry(path, version, start, List.copyOf(hashes), ref));
  }
  private static String relative(AgentRunContext owner, String path) throws java.io.IOException {
    try (var scope = AgentRunScope.open(owner)) {
      Path root = owner.projectRoot().toRealPath();
      return root.relativize(FileSnapshots.resolve(root, Path.of(path))).toString().replace('\\', '/');
    }
  }
  private static String hash(String text) { return FileSnapshots.hash(text.getBytes(StandardCharsets.UTF_8)); }
}
