package dev.mikoto2000.rei.llm;

import java.util.Objects;

/** Only capabilities Rei must validate; an unknown tool limit stays unknown. */
public record LlmCapabilities(ReasoningReplayMode reasoningReplay, Integer maxTools) {
  public LlmCapabilities {
    Objects.requireNonNull(reasoningReplay, "reasoningReplay");
    if (maxTools != null && maxTools < 1)
      throw new IllegalArgumentException("maxTools must be positive");
  }
}
