package dev.mikoto2000.rei.temporal;

public class ManualMonotonicTimeSource implements MonotonicTimeSource {
  private long nanos;

  public ManualMonotonicTimeSource(long nanos) {
    this.nanos = nanos;
  }

  @Override
  public long nanoTime() {
    return nanos;
  }

  public void advanceNanos(long delta) {
    nanos += delta;
  }
}
