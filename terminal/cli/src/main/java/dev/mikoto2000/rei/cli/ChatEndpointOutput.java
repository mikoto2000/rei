package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;

/** Presentation of the server's nonsecret endpoint projection. */
final class ChatEndpointOutput {
  private ChatEndpointOutput() {}

  static String label(JsonNode endpoint) {
    return "";
  }

  static String detail(JsonNode endpoint) {
    return "";
  }
}
