package dev.mikoto2000.rei.computeruse;
public record ComputerUseResult(Status status, int steps, String reason) {
  public enum Status { DONE, FAILED, MAX_STEPS, CANCELLED, MODEL_ERROR, CAPTURE_ERROR,
    ACTION_ERROR, STABILIZATION_ERROR, SAFETY_BLOCKED, BUSY }
}
