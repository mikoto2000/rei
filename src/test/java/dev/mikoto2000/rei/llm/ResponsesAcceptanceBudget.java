package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.modelmessage.ModelTokenUsage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/** Test-only global quota shared by independent accepted Runs, including child processes. */
final class ResponsesAcceptanceBudget {
  record Limits(int calls, long input, long output, Duration time, long microUsd,
      long inputMicroUsd, long outputMicroUsd) {}
  record Slot(int id, long input, long output) {}
  record Snapshot(int calls, int completed, int unknown, long input, long output, long microUsd) {}

  ResponsesAcceptanceBudget(Path journal, String binding, Limits limits, Clock clock) {}

  Slot reserve(long input, long output) { return new Slot(1, input, output); }
  void settle(Slot slot, ModelTokenUsage usage) {}
  void unknown(Slot slot) {}
  Snapshot snapshot() { return new Snapshot(0, 0, 0, 0, 0, 0); }
}
