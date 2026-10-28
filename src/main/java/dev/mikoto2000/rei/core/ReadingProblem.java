package dev.mikoto2000.rei.core;

/** Stable failure category and actionable recovery without exposing stored cursor bodies. */
public record ReadingProblem(String code, String message, String recovery) {
  static final class Failure extends IllegalArgumentException {
    final String code;
    Failure(String code) { super(code); this.code = code; }
  }
  static Failure fail(String code) { return new Failure(code); }
  static ReadingProblem from(Exception error) {
    Throwable cause = error;
    while (cause.getCause() != null && !(cause instanceof Failure)) cause = cause.getCause();
    String code = cause instanceof Failure failure ? failure.code
        : cause instanceof java.nio.file.NoSuchFileException ? "FILE_NOT_FOUND"
        : cause instanceof java.nio.file.AccessDeniedException ? "ACCESS_DENIED"
        : cause instanceof java.nio.charset.CharacterCodingException ? "INVALID_ENCODING"
        : "READ_FAILED";
    return new ReadingProblem(code, error.getMessage(), switch (code) {
      case "FILE_CHANGED", "CURSOR_EXPIRED", "INVALID_CURSOR" -> "Start a fresh search with the original conditions.";
      case "FILE_SIZE_LIMIT" -> "Narrow the source to supported files (at most 1 MiB each).";
      case "FILE_ENUMERATION_LIMIT", "SNAPSHOT_BUDGET", "RESULT_OUTPUT_BUDGET" -> "Use the returned cursor, or narrow the scope/budget.";
      default -> "Check the path, access permissions, encoding and request parameters.";
    });
  }
}
