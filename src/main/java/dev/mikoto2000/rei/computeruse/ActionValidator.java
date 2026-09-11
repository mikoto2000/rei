package dev.mikoto2000.rei.computeruse;

import java.util.Set;

public final class ActionValidator {
  public static final Set<String> KEYS = Set.of("ENTER", "TAB", "ESCAPE", "BACKSPACE", "DELETE",
      "UP", "DOWN", "LEFT", "RIGHT", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "SPACE");
  private ActionValidator() {}

  public static void validate(ComputerAction action, CapturedScreen screen) {
    if (action == null || action.risk() == null) throw new InvalidComputerDecision("Missing action or risk");
    switch (action) {
      case ComputerAction.Click a -> target(a.target(), a.confidence(), screen);
      case ComputerAction.DoubleClick a -> target(a.target(), a.confidence(), screen);
      case ComputerAction.TypeText a -> text(a.text(), 10000);
      case ComputerAction.PressKey a -> {
        if (!KEYS.contains(a.key() == null ? "" : a.key())) throw new InvalidComputerDecision("Unknown key");
      }
      case ComputerAction.Scroll a -> { if (a.amount() == 0 || Math.abs((long) a.amount()) > 20) throw new InvalidComputerDecision("Invalid scroll"); }
      case ComputerAction.Wait a -> { if (a.millis() < 1 || a.millis() > 10000) throw new InvalidComputerDecision("Invalid wait"); }
      case ComputerAction.Done a -> text(a.reason(), 300);
      case ComputerAction.Failed a -> text(a.reason(), 300);
      case ComputerAction.Uncertain a -> text(a.reason(), 300);
    }
  }

  static void text(String text, int limit) {
    if (text == null || text.isBlank() || text.length() > limit) throw new InvalidComputerDecision("Missing or oversized text");
  }

  private static void target(ComputerAction.Target target, double confidence, CapturedScreen screen) {
    if (target == null) throw new InvalidComputerDecision("Missing target");
    text(target.description(), 200);
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) throw new InvalidComputerDecision("Invalid confidence");
    screen.display(target.displayId()).desktopPoint(target);
  }
}
