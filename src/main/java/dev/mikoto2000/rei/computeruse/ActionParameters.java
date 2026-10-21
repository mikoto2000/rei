package dev.mikoto2000.rei.computeruse;

import java.util.Map;

/**
 * 画面操作の支援に必要な操作引数を扱う。
 *
 * <p>Shared non-positional parameters for planner history and diagnostic artifacts.
 */
final class ActionParameters {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ActionParameters() {}

  /** 画面操作の支援で受け渡す情報を、指定された内容から作成する。 */
  static Map<String, Object> of(ComputerAction action) {
    return switch (action) {
      case ComputerAction.PressKey a -> Map.of("key", a.key());
      case ComputerAction.Scroll a ->
          Map.of(
              "amount",
              a.amount(),
              "direction",
              a.amount() > 0 ? "down" : "up",
              "unit",
              "wheel_notches");
      case ComputerAction.Wait a -> Map.of("millis", a.millis());
      default -> Map.of();
    };
  }

  /** 過去の記録を、条件に従って参照できる形で返す。 */
  static String history(ComputerAction action) {
    var fields = of(action);
    if (fields.isEmpty()) return "";
    return " " + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(fields).toString();
  }
}
