package dev.mikoto2000.rei.core;

/** Stable failure category and actionable recovery without exposing stored cursor bodies. */
public record ReadingProblem(String code, String message, String recovery) {
  static final class Failure extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    final String code;

    Failure(String code) {
      super(code);
      this.code = code;
    }
  }

  static Failure fail(String code) {
    return new Failure(code);
  }

  public static ReadingProblem fromMessage(String message) {
    String text = java.util.Objects.toString(message, "Read failed");
    String code = "READ_FAILED";
    for (String candidate :
        java.util.List.of(
            "FILE_NOT_FOUND",
            "ACCESS_DENIED",
            "INVALID_ENCODING",
            "INVALID_ARGUMENT",
            "FILE_CHANGED",
            "SECTION_NOT_FOUND",
            "AMBIGUOUS_SECTION",
            "FILE_ENUMERATION_LIMIT",
            "FILE_SIZE_LIMIT",
            "SNAPSHOT_BUDGET",
            "SEARCH_PATTERN_BUDGET",
            "INVALID_CURSOR",
            "CURSOR_EXPIRED"))
      if (text.contains(candidate)) {
        code = candidate;
        break;
      }
    if (text.contains("STALE_VERSION") || text.contains("version mismatch")) code = "FILE_CHANGED";
    if (text.contains("Ambiguous symbol")) code = "AMBIGUOUS_SYMBOL";
    if (text.contains("Symbol not found")) code = "SYMBOL_NOT_FOUND";
    return new ReadingProblem(
        code,
        text,
        code.startsWith("AMBIGUOUS")
            ? "Choose an exact candidate identifier and expectedVersion."
            : code.equals("SEARCH_PATTERN_BUDGET")
                ? "Use a simpler pattern, fixedString, or a narrower scope."
                : "Check the source, permissions and encoding; retry with current coordinates or a narrower request.");
  }

  static ReadingProblem from(Exception error) {
    Throwable cause = error;
    while (cause.getCause() != null && !(cause instanceof Failure)) cause = cause.getCause();
    String code =
        cause instanceof Failure failure
            ? failure.code
            : cause instanceof java.nio.file.NoSuchFileException
                ? "FILE_NOT_FOUND"
                : cause instanceof java.nio.file.AccessDeniedException
                    ? "ACCESS_DENIED"
                    : cause instanceof java.nio.charset.CharacterCodingException
                        ? "INVALID_ENCODING"
                        : error instanceof IllegalArgumentException
                            ? "INVALID_ARGUMENT"
                            : fromMessage(error.getMessage()).code();
    return new ReadingProblem(
        code,
        error.getMessage(),
        switch (code) {
          case "FILE_CHANGED", "CURSOR_EXPIRED", "INVALID_CURSOR" ->
              "Start a fresh search with the original conditions.";
          case "FILE_SIZE_LIMIT" -> "Narrow the source to supported files (at most 1 MiB each).";
          case "FILE_ENUMERATION_LIMIT", "SNAPSHOT_BUDGET", "RESULT_OUTPUT_BUDGET" ->
              "Use the returned cursor, or narrow the scope/budget.";
          default -> "Check the path, access permissions, encoding and request parameters.";
        });
  }
}
