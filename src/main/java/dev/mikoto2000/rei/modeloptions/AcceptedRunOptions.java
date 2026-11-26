package dev.mikoto2000.rei.modeloptions;

/** Read-only accepted Run settings needed by native option transformations. */
public interface AcceptedRunOptions {
  String fingerprint();

  boolean named();

  boolean supportsOutputLimit();

  Integer maxOutputTokens();

  /** Public, credential-free destination details; unknown implementations cannot infer a route. */
  default java.util.Map<String, String> inferenceDescription() {
    return java.util.Map.of();
  }
}
