package dev.mikoto2000.rei.autonomous;

import java.util.function.BooleanSupplier;

/** Fixed-record transport. FAILED means that no createRecord attempt was made. */
public interface AutonomousPublicationTransport {
  record Receipt(
      AutonomousPublicationStore.Outcome outcome, String uri, String cid, String failureCode) {}

  /** Local configuration and text validation only; performs no authentication or network I/O. */
  void validate(String text);

  Receipt send(AutonomousPostRecord record, BooleanSupplier canSend);
}
