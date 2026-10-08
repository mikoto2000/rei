package dev.mikoto2000.rei.http;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** Payload volume across a bounded operation; cached decoded bodies also consume the logical volume. */
public final class TransferBudget {
  private final long maximumWire, maximumDecoded;
  private final AtomicLong wire = new AtomicLong(), decoded = new AtomicLong();
  private final String identity = UUID.randomUUID().toString();
  public TransferBudget(long maximumWire, long maximumDecoded) {
    if (maximumWire < 1 || maximumDecoded < 1 || maximumWire > 512L * 1024 * 1024 || maximumDecoded > 512L * 1024 * 1024)
      throw new IllegalArgumentException("Invalid aggregate transfer limits");
    this.maximumWire = maximumWire; this.maximumDecoded = maximumDecoded;
  }
  public void wire(long bytes) { reserve(wire, bytes, maximumWire); }
  public void decoded(long bytes) { reserve(decoded, bytes, maximumDecoded); }
  private static void reserve(AtomicLong count, long incoming, long maximum) {
    if (incoming < 0) throw new IllegalArgumentException("Negative transfer size");
    for (;;) { long current = count.get();
      if (incoming > maximum - current) throw new HttpFetchException(HttpFetchException.Code.TRANSFER_BUDGET);
      if (count.compareAndSet(current, current + incoming)) return;
    }
  }
  public long wireBytes() { return wire.get(); }
  public long decodedBytes() { return decoded.get(); }
  public String identity() { return identity; }
}
