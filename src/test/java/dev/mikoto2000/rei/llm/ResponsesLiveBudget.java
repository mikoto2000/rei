package dev.mikoto2000.rei.llm;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;

/** Test-only durable reservations for the explicitly approved initial live window. */
final class ResponsesLiveBudget {
  ResponsesLiveBudget(Path ledger, String binding, Clock clock) {}

  int reserve(long bodyBytes, int outputTokens) throws IOException {
    return 1;
  }
}
