package dev.mikoto2000.rei.llm;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;

/** Test-only durable reservations for the explicitly approved initial live window. */
final class ResponsesLiveBudget {
  private final Path ledger;
  private final String binding;
  private final Clock clock;
  private long deadline;

  ResponsesLiveBudget(Path ledger, String binding, Clock clock) {
    if (!binding.matches("[A-Za-z0-9_-]+")) throw rejected();
    this.ledger = ledger;
    this.binding = binding;
    this.clock = clock;
  }

  int reserve(long bodyBytes, int outputTokens) throws IOException {
    if (bodyBytes < 0 || bodyBytes + 2048 > 10000 || outputTokens < 1 || outputTokens > 2000)
      throw rejected();
    try (var channel = FileChannel.open(ledger, StandardOpenOption.CREATE, StandardOpenOption.READ,
            StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      if (!lock.isValid() || channel.size() > 4096) throw rejected();
      var bytes = ByteBuffer.allocate((int) channel.size());
      while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw rejected();
      String journal = new String(bytes.array(), StandardCharsets.UTF_8);
      if (!journal.isEmpty() && !journal.endsWith("\n")) throw rejected();
      long started = clock.millis();
      int count = 0;
      for (String row : journal.split("\n")) {
        if (row.isEmpty()) continue;
        String[] fields = row.split(",", -1);
        if (fields.length != 3 || !binding.equals(fields[2])) throw rejected();
        try {
          long stamp = Long.parseLong(fields[0]);
          int sequence = Integer.parseInt(fields[1]);
          if (sequence != count + 1 || count > 0 && started != stamp) throw rejected();
          started = stamp;
          count = sequence;
        } catch (NumberFormatException invalid) {
          throw rejected();
        }
      }
      long elapsed = clock.millis() - started;
      if (count >= 10 || elapsed < 0 || elapsed >= 900000) throw rejected();
      deadline = started + 900000;
      var next = ByteBuffer.wrap(
          (started + "," + (count + 1) + "," + binding + "\n").getBytes(StandardCharsets.UTF_8));
      channel.position(channel.size());
      while (next.hasRemaining()) channel.write(next);
      channel.force(true);
      return count + 1;
    }
  }

  long remainingMillis() {
    long remaining = deadline - clock.millis();
    if (remaining <= 0) throw rejected();
    return remaining;
  }

  private static IllegalStateException rejected() {
    return new IllegalStateException("Approved initial live budget rejected");
  }
}
