package dev.mikoto2000.rei.agent.progress;

import java.util.List;

public record ProgressEvaluation(
    ProgressLevel level,
    List<String> reasons) {

  public ProgressEvaluation {
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }

  public boolean progressed() {
    return level == ProgressLevel.MEANINGFUL;
  }
}
