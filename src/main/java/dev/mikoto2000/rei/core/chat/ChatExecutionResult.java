package dev.mikoto2000.rei.core.chat;

public record ChatExecutionResult(Status status, String text, boolean memoryConsolidationSuggested,
    String errorMessage) {

  public static ChatExecutionResult success(String text, boolean memoryConsolidationSuggested) {
    return new ChatExecutionResult(Status.SUCCESS, text == null ? "" : text, memoryConsolidationSuggested, null);
  }

  public static ChatExecutionResult failed(String errorMessage) {
    return new ChatExecutionResult(Status.FAILED, "", false, errorMessage);
  }

  public boolean success() {
    return status == Status.SUCCESS;
  }

  public enum Status {
    SUCCESS,
    FAILED
  }
}
