package dev.mikoto2000.rei.subagent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Provider-independent, immutable configuration for an ephemeral execution. */
public record SubAgentDefinition(String id, String name, String description, String systemPrompt,
    List<String> requestedTools, String model, int maxSteps, Duration timeout, Path source) {
  public SubAgentDefinition {
    if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("id: expected [a-z][a-z0-9-]{0,63}");
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name: required");
    if (description == null || description.isBlank()) throw new IllegalArgumentException("description: required");
    if (systemPrompt == null || systemPrompt.isBlank()) throw new IllegalArgumentException("systemPrompt: required");
    if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps: must be positive");
    if (timeout == null || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout: must be positive");
    try { timeout.toNanos(); } catch (ArithmeticException e) { throw new IllegalArgumentException("timeout: too large"); }
    requestedTools = List.copyOf(requestedTools);
    source = source.toAbsolutePath().normalize();
  }
}
