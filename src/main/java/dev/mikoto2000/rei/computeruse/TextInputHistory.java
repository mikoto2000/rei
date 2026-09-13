package dev.mikoto2000.rei.computeruse;

import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Per-run dispatch ledger, independent of the planner's bounded history. */
final class TextInputHistory {
  private record Entry(String text, String elementId) {}
  private final List<Entry> entries = new ArrayList<>();
  private static final ObjectMapper JSON = new ObjectMapper();

  void check(String text, String focus) {
    var state = parse(focus);
    String id = identity(state);
    boolean repeated = entries.stream().anyMatch(e -> e.text().equals(text)
        && (id == null || e.elementId() == null || id.equals(e.elementId())));
    if (!repeated) return;
    boolean readable = "ok".equals(state.path("status").asText()) && state.path("hasKeyboardFocus").asBoolean()
        && state.path("enabled").asBoolean() && state.path("editable").asBoolean() && !state.path("password").asBoolean()
        && "ok".equals(state.path("valueStatus").asText()) && state.path("value").isTextual();
    // Retry only when the same known field is explicitly empty; lack of evidence is not emptiness.
    if (id != null && readable && state.get("value").asText().isEmpty()
        && entries.stream().anyMatch(e -> id.equals(e.elementId()) && text.equals(e.text()))) return;
    throw new InvalidComputerDecision(readable && state.get("value").asText().contains(text)
        ? "Duplicate text input blocked: requested text is already present in the focused field"
        : "Repeated text input blocked: field is nonempty or input result cannot be verified");
  }

  void dispatched(String text, String focus) { entries.add(new Entry(text,identity(parse(focus)))); }

  static String summary(String text, String focus) {
    var data = JSON.createObjectNode().put("typedText",text);
    data.put("elementId",identity(parse(focus)));
    return data.toString();
  }

  private static String identity(JsonNode state) {
    if (!"ok".equals(state.path("status").asText()) || !state.path("hasKeyboardFocus").asBoolean()) return null;
    var id = state.path("elementId");
    return id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
  }
  private static JsonNode parse(String focus) {
    try { var n=JSON.readTree(focus); return n==null ? JSON.createObjectNode() : n; }
    catch(Exception e) { return JSON.createObjectNode(); }
  }
}
