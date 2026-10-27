package dev.mikoto2000.rei.httpapi;

import tools.jackson.databind.JsonNode;
import java.util.Map;

/** Structured API input. Omitted body sends no bytes; JSON null sends the literal null. */
public record HttpApiRequest(
    String url, String method, Map<String, String> query, Map<String, String> headers,
    String jsonPointer, Integer maxItems, String authProfile, JsonNode body) {}
