package dev.mikoto2000.rei.core.completion;

/** Unquoted insertion text; escaping and presentation belong to the input adapter. */
public record CompletionCandidate(String value, String display, String description, String kind, boolean appendSpace) {
  public static CompletionCandidate value(String value, String kind) {
    return new CompletionCandidate(value, value, null, kind, true);
  }
}
