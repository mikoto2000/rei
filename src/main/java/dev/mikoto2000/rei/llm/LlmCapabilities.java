package dev.mikoto2000.rei.llm;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Only capabilities Rei must validate; an unknown tool limit stays unknown. */
public record LlmCapabilities(
    ReasoningReplayMode reasoningReplay, Integer maxTools, Set<Setting> supportedSettings) {
  /** Settings whose explicit use requires a declaration rather than a provider guess. */
  public enum Setting {
    TEMPERATURE,
    TOP_P,
    REASONING_EFFORT,
    REASONING_SUMMARY,
    TEXT_VERBOSITY,
    PARALLEL_TOOL_CALLS,
    STRUCTURED_OUTPUT,
    IMAGE_INPUT
  }

  public LlmCapabilities(ReasoningReplayMode reasoningReplay, Integer maxTools) {
    this(reasoningReplay, maxTools, Set.of());
  }

  /** Backend-only request context; never an HTTP body field. */
  public static final String MAX_TOOLS_CONTEXT_KEY = "rei.llm.max-tools";

  public LlmCapabilities {
    Objects.requireNonNull(reasoningReplay, "reasoningReplay");
    Objects.requireNonNull(supportedSettings, "supportedSettings");
    var ordered = EnumSet.noneOf(Setting.class);
    ordered.addAll(supportedSettings);
    supportedSettings = Collections.unmodifiableSet(ordered);
    if (maxTools != null && maxTools < 1)
      throw new IllegalArgumentException("maxTools must be positive");
  }
}
