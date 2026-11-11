package dev.mikoto2000.rei.output;

/** Provides fixed diagnostic codes for logging a validation failure without logging output data. */
public interface OutputValidationDiagnostic {
  /** Returns a field path defined by the output schema. */
  String fieldPath();

  /** Returns a fixed reason code for the failed validation rule. */
  String reason();
}
