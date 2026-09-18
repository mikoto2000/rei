package dev.mikoto2000.rei.subagent;

import java.util.List;

public record ValidationResult(boolean valid, List<ValidationError> errors) {
  public ValidationResult {
    errors = List.copyOf(errors);
    if (valid != errors.isEmpty()) throw new IllegalArgumentException("valid must agree with errors");
  }
  public static ValidationResult of(List<ValidationError> errors) {
    return new ValidationResult(errors.isEmpty(), errors);
  }
}
