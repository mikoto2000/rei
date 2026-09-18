package dev.mikoto2000.rei.subagent;

import java.util.List;

/** Does not retain raw model output or parser/library exceptions containing it. */
public final class SubAgentValidationException extends RuntimeException {
  private final List<ValidationError> errors;
  public SubAgentValidationException(List<ValidationError> errors) {
    super("SubAgent structural validation failed");
    this.errors = List.copyOf(errors);
  }
  public List<ValidationError> errors() { return errors; }
}
