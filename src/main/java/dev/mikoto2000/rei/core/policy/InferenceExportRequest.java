package dev.mikoto2000.rei.core.policy;

import org.springframework.ai.chat.prompt.Prompt;

/** Private, short-lived input for content-bound inference approval; never a persisted request. */
final class InferenceExportRequest {
  private InferenceExportRequest() {}

  static InferenceExportRequest prepare(Prompt prompt) {
    throw new UnsupportedOperationException("Inference export preparation is not implemented");
  }

  Prompt prompt() {
    throw new UnsupportedOperationException("Inference export preparation is not implemented");
  }

  String fingerprint() {
    throw new UnsupportedOperationException("Inference export preparation is not implemented");
  }
}
