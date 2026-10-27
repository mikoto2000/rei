package dev.mikoto2000.rei.httpapi;

import tools.jackson.databind.JsonNode;

/** HTTP status is independent of transport, parsing and extraction failures. */
public record HttpApiResult(
    Integer status,
    String contentType,
    JsonNode json,
    String text,
    boolean extracted,
    boolean truncated,
    String error,
    boolean timedOut,
    boolean cancelled,
    boolean outcomeUnknown,
    int responseBytes,
    int outputBytes) {}
