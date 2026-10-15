package dev.mikoto2000.rei.subagent;

import java.util.*;
import tools.jackson.databind.JsonNode;

/**
 * Independent mechanical checks after schema and snapshot citation validation. Not an LLM verdict.
 */
public final class PlanReviewSemanticValidator {
  public enum Support {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    UNSUPPORTED,
    UNVERIFIED
  }

  public record Verdict(Support support, boolean truthVerified, List<ValidationError> issues) {
    public Verdict {
      issues = List.copyOf(issues);
    }
  }

  public Verdict validate(JsonNode wrapper) {
    var errors = new ArrayList<ValidationError>();
    var seen = new HashMap<String, String>();
    var paths = new HashSet<String>();
    var quotes = new ArrayList<String>();
    for (var reference : wrapper.path("references")) paths.add(reference.path("path").asString());
    for (var reference : wrapper.path("references")) quotes.add(reference.path("quote").asString());
    boolean literal = true;
    for (var finding : wrapper.path("findings")) {
      String message = finding.path("message").asString();
      String location = finding.path("location").asString();
      String severity = finding.path("severity").asString();
      String previous = seen.putIfAbsent(location + "\n" + message, severity);
      if (previous != null)
        errors.add(
            new ValidationError(
                "/findings",
                previous.equals(severity)
                    ? "Duplicate finding"
                    : "Contradictory finding severity"));
      literal &= !message.isBlank() && quotes.stream().anyMatch(q -> q.contains(message));
      if (location.isBlank()
          || paths.stream()
              .noneMatch(path -> location.equals(path) || location.startsWith(path + ":")))
        errors.add(
            new ValidationError("/findings/location", "Finding has no cited source location"));
    }
    // SUCCESS that explicitly reports unverified work cannot claim full support.
    Support support =
        !errors.isEmpty()
            ? Support.UNSUPPORTED
            : paths.isEmpty()
                ? Support.UNVERIFIED
                : literal
                        && !wrapper.path("findings").isEmpty()
                        && wrapper.path("unverified").isEmpty()
                    ? Support.SUPPORTED
                    : Support.PARTIALLY_SUPPORTED;
    return new Verdict(support, false, errors);
  }
}
