package dev.mikoto2000.rei.computeruse;

/** Exactly one decision; terminal and uncertain decisions never dispatch input. */
public sealed interface ComputerAction {
  enum Risk { LOW, CONFIRM_REQUIRED, PROHIBITED }
  record Target(String displayId, int x, int y, String description) {
    public Target(int x, int y, String description) { this(null, x, y, description); }
  }
  default Risk risk() { return Risk.LOW; }
  record Click(Target target, double confidence, Risk risk) implements ComputerAction {}
  record DoubleClick(Target target, double confidence, Risk risk) implements ComputerAction {}
  record TypeText(String text, Risk risk) implements ComputerAction {}
  record PressKey(String key, Risk risk) implements ComputerAction {}
  record Scroll(int amount, Risk risk) implements ComputerAction {}
  record Wait(long millis) implements ComputerAction {}
  record Done(String reason) implements ComputerAction {}
  record Failed(String reason) implements ComputerAction {}
  record Uncertain(String reason) implements ComputerAction {}
}
