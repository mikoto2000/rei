package dev.mikoto2000.rei.llm;

import java.util.Objects;

/** Only capabilities Rei must validate; an unknown tool limit stays unknown. */
public record LlmCapabilities(ReasoningReplayMode reasoningReplay, Integer maxTools) {
  /** Backend-only request context; never an HTTP body field. */
  public static final String MAX_TOOLS_CONTEXT_KEY = "rei.llm.max-tools";

  public LlmCapabilities {
    Objects.requireNonNull(reasoningReplay, "reasoningReplay");
    if (maxTools != null && maxTools < 1)
      throw new IllegalArgumentException("maxTools must be positive");
  }
}
