package dev.mikoto2000.rei.httpapi;

import java.util.Map;
import org.springframework.ai.tool.annotation.ToolParam;
import tools.jackson.databind.JsonNode;

/** Structured API input. Omitted body sends no bytes; JSON null sends the literal null. */
public record HttpApiRequest(
    String url,
    @ToolParam(required = false) String method,
    @ToolParam(required = false) Map<String, String> query,
    @ToolParam(required = false) Map<String, String> headers,
    @ToolParam(required = false) String jsonPointer,
    @ToolParam(required = false) Integer maxItems,
    @ToolParam(required = false) String authProfile,
    @ToolParam(required = false) JsonNode body) {
  /** Freeze request metadata before permission checks and asynchronous network execution. */
  public HttpApiRequest {
    query = query == null ? null : Map.copyOf(query);
    headers = headers == null ? null : Map.copyOf(headers);
  }
}
