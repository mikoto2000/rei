package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Human meeting text provided by the server; structured data remains available with --json. */
final class SecretaryMeetingOutput {
  private SecretaryMeetingOutput() {}

  static String text(JsonNode response) {
    var meeting =
        response.has("report") ? response.path("report").path("meeting") : response.path("meeting");
    if (meeting.path("text").isTextual()) return meeting.path("text").asText();
    if (response.has("selected") && response.has("saved") && response.has("review"))
      return "方針を保存しました。ミーティングを終了します。";
    return null;
  }

  static Map<String, Object> confirmation(
      JsonNode report, String requestPath, String key, List<String> selected) {
    var body = new LinkedHashMap<String, Object>();
    body.put("date", report.path("date").asText());
    body.put("review", report.path("review").asBoolean());
    body.put("key", key);
    body.put("selected", selected);
    // Preserve the report request scope rather than the client's current Project.
    String query = URI.create(requestPath).getRawQuery();
    if (query != null) {
      for (String parameter : query.split("&")) {
        if (parameter.startsWith("projectId="))
          body.put("projectId", URLDecoder.decode(parameter.substring(10), StandardCharsets.UTF_8));
      }
    }
    return body;
  }
}
