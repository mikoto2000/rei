package dev.mikoto2000.rei.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 本文を保存せず、要求と実際の返却ページを対応付ける診断用の要約。 */
final class ReadMultiFileLogSummary {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String[] REQUEST_FIELDS = {
    "path", "startLine", "endLine", "offset", "expectedVersion", "charset",
    "symbol", "symbolOffset", "section", "sectionId", "headingLevel", "includeChildren",
    "includeBody", "includeJavadoc", "includeOwnerOverview", "contextLines", "unreadOnly"
  };

  private ReadMultiFileLogSummary() {}

  /** 不正な結果でも診断の失敗でツール呼び出しを失敗させない。本文へのフォールバックはしない。 */
  static String summarize(String input, String output) {
    try {
      JsonNode requests = JSON.readTree(input).path("files");
      JsonNode results = JSON.readTree(output);
      if (!requests.isArray() || !results.isArray() || requests.size() != results.size())
        return "[readMultiFile] metadata unavailable";
      var pages = JSON.createArrayNode();
      for (int index = 0; index < Math.min(results.size(), 20); index++) {
        JsonNode result = results.get(index);
        var page = pages.addObject();
        page.put("index", index);
        page.set("request", request(requests.get(index)));
        var returned = page.putObject("returned");
        copy(result, returned, "path", "version", "truncated", "truncationReason", "returnedBytes");
        int lines = result.path("content").isArray() ? result.path("content").size() : 0;
        returned.put("lineCount", lines);
        // Empty/budget-only results may echo requested bounds; they returned no source range.
        if (lines > 0) copy(result, returned, "startLine", "endLine");
        else {
          returned.putNull("startLine");
          returned.putNull("endLine");
        }
        returned.put("errorPresent", result.hasNonNull("error"));
        if (result.path("symbol").isObject())
          copy(result.path("symbol"), returned.putObject("symbol"), "symbolId");
        if (result.hasNonNull("nextRead")) page.set("nextRead", request(result.path("nextRead")));
      }
      if (results.size() > 20) pages.addObject().put("omittedFiles", results.size() - 20);
      return "[readMultiFile] " + CredentialRedactor.redact(JSON.writeValueAsString(pages));
    } catch (Exception ignored) {
      return "[readMultiFile] metadata unavailable";
    }
  }

  private static ObjectNode request(JsonNode source) {
    var target = JSON.createObjectNode();
    copy(source, target, REQUEST_FIELDS);
    if (source.path("sectionPath").isArray()) {
      var path = target.putArray("sectionPath");
      for (int i = 0; i < Math.min(source.path("sectionPath").size(), 20); i++)
        path.add(bounded(source.path("sectionPath").get(i).asText()));
    }
    return target;
  }

  private static void copy(JsonNode source, ObjectNode target, String... fields) {
    for (String field : fields) {
      JsonNode value = source.get(field);
      if (value == null || value.isNull()) target.putNull(field);
      else if (value.isTextual()) target.put(field, bounded(value.asText()));
      else if (value.isNumber() || value.isBoolean()) target.set(field, value);
    }
  }

  private static String bounded(String value) {
    return value.length() <= 1024 ? value : value.substring(0, 1024) + "...";
  }
}
