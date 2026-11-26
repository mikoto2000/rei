package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;

/** Presentation of the server's nonsecret endpoint projection. */
final class ChatEndpointOutput {
  private ChatEndpointOutput() {}

  static String label(JsonNode endpoint) {
    var profile = endpoint.path("profile");
    var account = profile.path("accountRef");
    String reason = endpoint.path("unavailableReason").asText("");
    return safe(profile.path("id").asText()
        + (endpoint.path("active").asBoolean() ? " [active]" : "")
        + " | " + profile.path("provider").asText() + " / "
        + profile.path("api").asText().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
        + " | " + profile.path("model").asText() + " | " + profile.path("usagePath").asText()
        + " | account=" + (account.isNull() || account.isMissingNode() ? "-" : account.asText())
        + " | auth=" + profile.path("authentication").asText() + " / "
        + endpoint.path("authenticationState").asText()
        + (reason.isEmpty() ? "" : " | unavailable: " + reason));
  }

  static String detail(JsonNode endpoint) {
    var result = new StringBuilder(label(endpoint));
    result.append("\ncapabilities: ").append(safe(endpoint.path("profile").path("capabilities").path("effective").toString()));
    result.append("\ngeneration: ").append(safe(endpoint.path("generationSettings").toString()));
    result.append("\nunapplied: ").append(safe(endpoint.path("unappliedSettings").toString()));
    for (var diagnostic : endpoint.path("diagnostics")) result.append('\n').append(safe(diagnostic.asText()));
    result.append('\n').append(safe(endpoint.path("selectionScope").asText()));
    return result.toString();
  }

  private static String safe(String text) {
    return text.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ");
  }
}
