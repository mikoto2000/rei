package dev.mikoto2000.rei.application.run;

public enum RunStatus {
  QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;
  public boolean isTerminal() { return this == COMPLETED || this == FAILED || this == CANCELLED; }
}
