package dev.mikoto2000.rei.computeruse;

import java.util.Set;

/** 画面操作の支援に必要な操作検証の窓口を扱う。 */
public final class ActionValidator {
  /** キー一覧。 */
  public static final Set<String> KEYS =
      Set.of(
          "ENTER",
          "TAB",
          "ESCAPE",
          "BACKSPACE",
          "DELETE",
          "UP",
          "DOWN",
          "LEFT",
          "RIGHT",
          "HOME",
          "END",
          "PAGE_UP",
          "PAGE_DOWN",
          "SPACE");

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ActionValidator() {}

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public static void validate(ComputerAction action, CapturedScreen screen) {
    if (action == null || action.risk() == null)
      throw new InvalidComputerDecision("Missing action or risk");
    switch (action) {
      case ComputerAction.Click a -> target(a.target(), a.confidence(), screen);
      case ComputerAction.DoubleClick a -> target(a.target(), a.confidence(), screen);
      case ComputerAction.TypeText a -> text(a.text(), 10000);
      case ComputerAction.PressKey a -> {
        if (!KEYS.contains(a.key() == null ? "" : a.key()))
          throw new InvalidComputerDecision("Unknown key");
      }
      case ComputerAction.Scroll a -> {
        if (a.amount() == 0 || Math.abs((long) a.amount()) > 20)
          throw new InvalidComputerDecision("Invalid scroll");
      }
      case ComputerAction.Wait a -> {
        if (a.millis() < 1 || a.millis() > 10000) throw new InvalidComputerDecision("Invalid wait");
      }
      case ComputerAction.Done a -> text(a.reason(), 300);
      case ComputerAction.Failed a -> text(a.reason(), 300);
      case ComputerAction.Uncertain a -> text(a.reason(), 300);
    }
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  static void text(String text, int limit) {
    if (text == null || text.isBlank() || text.length() > limit)
      throw new InvalidComputerDecision("Missing or oversized text");
  }

  /** 画面操作の支援に関する対象を処理する。 */
  private static void target(
      ComputerAction.Target target, double confidence, CapturedScreen screen) {
    if (target == null) throw new InvalidComputerDecision("Missing target");
    text(target.description(), 200);
    if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1)
      throw new InvalidComputerDecision("Invalid confidence");
    screen.display(target.displayId()).desktopPoint(target);
  }
}
