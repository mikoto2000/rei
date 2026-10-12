package dev.mikoto2000.rei.application.run;

public enum RunStatus {
  QUEUED, RUNNING, WAITING, WAITING_APPROVAL, COMPLETED, FAILED, CANCELLED, UNKNOWN;
  public boolean isWaiting(){return this==WAITING||this==WAITING_APPROVAL;}
  public boolean isTerminal() { return this == COMPLETED || this == FAILED || this == CANCELLED || this == UNKNOWN; }
}
