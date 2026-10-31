package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;

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
}
