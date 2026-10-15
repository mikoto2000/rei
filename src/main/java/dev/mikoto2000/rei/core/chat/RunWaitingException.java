package dev.mikoto2000.rei.core.chat;

/** Control flow after the tool batch is durably saved; never a failed or replayable tool. */
public final class RunWaitingException extends RuntimeException {
  public RunWaitingException() {
    super("Run suspended for a saved continuation", null, false, false);
  }
}
