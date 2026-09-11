package dev.mikoto2000.rei.computeruse;

/** Contains only local validation diagnostics, never provider output or typed text. */
final class InvalidComputerDecision extends IllegalArgumentException {
  InvalidComputerDecision(String diagnostic) { super(diagnostic); }
}
