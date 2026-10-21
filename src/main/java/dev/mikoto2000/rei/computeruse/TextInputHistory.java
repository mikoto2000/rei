package dev.mikoto2000.rei.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * 画面操作の支援に必要なテキスト入力履歴を扱う。
 *
 * <p>Per-run dispatch ledger, independent of the planner's bounded history.
 */
final class TextInputHistory {
  /**
   * 画面操作の支援で受け渡す項目を表す。
   *
   * @param text テキスト
   * @param elementId 要素識別子
   */
  private record Entry(String text, String elementId) {}

  /** 項目一覧。 */
  private final List<Entry> entries = new ArrayList<>();

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  void check(String text, String focus) {
    var state = parse(focus);
    String id = identity(state);
    boolean repeated =
        entries.stream()
            .anyMatch(
                e ->
                    e.text().equals(text)
                        && (id == null || e.elementId() == null || id.equals(e.elementId())));
    if (!repeated) return;
    boolean readable =
        "ok".equals(state.path("status").asText())
            && state.path("hasKeyboardFocus").asBoolean()
            && state.path("enabled").asBoolean()
            && state.path("editable").asBoolean()
            && !state.path("password").asBoolean()
            && "ok".equals(state.path("valueStatus").asText())
            && state.path("value").isTextual();
    // Retry only when the same known field is explicitly empty; lack of evidence is not emptiness.
    if (id != null
        && readable
        && state.get("value").asText().isEmpty()
        && entries.stream().anyMatch(e -> id.equals(e.elementId()) && text.equals(e.text())))
      return;
    throw new InvalidComputerDecision(
        readable && state.get("value").asText().contains(text)
            ? "Duplicate text input blocked: requested text is already present in the focused field"
            : "Repeated text input blocked: field is nonempty or input result cannot be verified");
  }

  /** 画面操作の支援に関する引き渡し済みを処理する。 */
  void dispatched(String text, String focus) {
    entries.add(new Entry(text, identity(parse(focus))));
  }

  /** 画面操作の支援で用いる要約を返す。 */
  static String summary(String text, String focus) {
    var data = JSON.createObjectNode().put("typedText", text);
    data.put("elementId", identity(parse(focus)));
    return data.toString();
  }

  /** 画面操作の支援で用いる識別情報を返す。 */
  private static String identity(JsonNode state) {
    if (!"ok".equals(state.path("status").asText()) || !state.path("hasKeyboardFocus").asBoolean())
      return null;
    var id = state.path("elementId");
    return id.isTextual() && !id.asText().isBlank() ? id.asText() : null;
  }

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  private static JsonNode parse(String focus) {
    try {
      var n = JSON.readTree(focus);
      return n == null ? JSON.createObjectNode() : n;
    } catch (Exception e) {
      return JSON.createObjectNode();
    }
  }
}
