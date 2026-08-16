package dev.mikoto2000.rei.temporal;

public interface MonotonicTimeSource {
  long nanoTime();

  static MonotonicTimeSource system() {
    return System::nanoTime;
  }
}
