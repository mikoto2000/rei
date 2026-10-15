package dev.mikoto2000.rei.mcp;

import java.time.Duration;

public record McpAdmission(
    String requestHash,
    String fingerprint,
    Duration retention,
    int totalCriteria,
    java.util.List<String> criteria) {
  public McpAdmission {
    criteria = java.util.List.copyOf(criteria);
    if (requestHash == null
        || !requestHash.matches("[a-f0-9]{64}")
        || fingerprint == null
        || !fingerprint.matches("[a-f0-9]{64}")
        || retention == null
        || retention.isNegative()
        || retention.isZero()) throw new IllegalArgumentException("Invalid MCP admission");
  }
}
