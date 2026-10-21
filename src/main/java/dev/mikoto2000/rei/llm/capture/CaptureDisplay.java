package dev.mikoto2000.rei.llm.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * モデル応答の記録に必要な取得記録表示を扱う。
 *
 * <p>Display only: never replaces the original bytes.
 */
final class CaptureDisplay {
  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final ObjectMapper JSON =
      new ObjectMapper(
          com.fasterxml.jackson.core.JsonFactory.builder()
              .streamReadConstraints(
                  com.fasterxml.jackson.core.StreamReadConstraints.builder()
                      .maxNestingDepth(100)
                      .maxStringLength(CaptureStore.BODY_LIMIT)
                      .build())
              .build());

  /** モデル応答の記録で用いる非公開化した内容を返す。 */
  static String masked(byte[] bytes) {
    try {
      var tree = JSON.readTree(bytes);
      if (tree.isTextual()) tree = TextNode.valueOf(text(tree.textValue()));
      else mask(tree, 0, new int[] {0});
      String model = tree.path("model").isTextual() ? tree.path("model").textValue() : "不明";
      return "model (HTTP body): "
          + escape(model)
          + "\n"
          + escape(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tree));
    } catch (Exception unsupported) {
      return "JSON表示を省略しました（非JSON、深さまたは表示要素数の上限）。原本は変更していません。";
    }
  }

  /** モデル応答の記録に関する非公開化を処理する。 */
  private static void mask(JsonNode node, int depth, int[] count) {
    if (depth > 100 || ++count[0] > 65536) throw new IllegalArgumentException("Display limit");
    if (node instanceof ObjectNode object) {
      var fields = object.fields();
      while (fields.hasNext()) {
        var entry = fields.next();
        String key = entry.getKey().toLowerCase(java.util.Locale.ROOT).replace("-", "_");
        if (key.matches(
            ".*(api_?key|password|passwd|secret|authorization|access_token|refresh_token|cookie|b64_json|base64|audio_data|^token$|^data$).*"))
          object.put(entry.getKey(), "[REDACTED]");
        else if (entry.getValue().isTextual())
          object.put(entry.getKey(), text(entry.getValue().textValue()));
        else mask(entry.getValue(), depth + 1, count);
      }
    } else if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        var item = array.get(i);
        if (item.isTextual()) array.set(i, TextNode.valueOf(text(item.textValue())));
        else mask(item, depth + 1, count);
      }
    }
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static String text(String value) {
    if (value.startsWith("data:") || value.length() >= 40 && value.matches("[A-Za-z0-9+/=\\r\\n]+"))
      return "[REDACTED media/base64]";
    return value
        .replaceAll("(?i)Bearer\\s+[^\\s\"']+", "Bearer [REDACTED]")
        .replaceAll("sk-[A-Za-z0-9_-]+", "[REDACTED]")
        .replaceAll("([A-Za-z][A-Za-z0-9+.-]*://)[^\\s/@]+:[^\\s/@]+@", "$1[REDACTED]@")
        .replaceAll("(?i)(password|api[_-]?key|token)(\\s*[:=]\\s*)[^\\s,;]+", "$1$2[REDACTED]");
  }

  /** モデル応答の記録で用いる特殊文字の保護を返す。 */
  static String escape(String text) {
    if (text == null) return "unknown";
    var out = new StringBuilder();
    text.codePoints()
        .forEach(
            c -> {
              if (c != '\n'
                  && c != '\t'
                  && (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT))
                out.append(String.format("\\u%04x", c));
              else out.appendCodePoint(c);
            });
    return out.toString();
  }
}
