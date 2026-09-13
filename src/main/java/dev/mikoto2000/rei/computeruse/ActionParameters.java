package dev.mikoto2000.rei.computeruse;

import java.util.Map;

/** Shared non-positional parameters for planner history and diagnostic artifacts. */
final class ActionParameters {
  private ActionParameters() {}
  static Map<String,Object> of(ComputerAction action) {
    return switch (action) {
      case ComputerAction.PressKey a -> Map.of("key",a.key());
      case ComputerAction.Scroll a -> Map.of("amount",a.amount(),"direction",a.amount()>0 ? "down" : "up","unit","wheel_notches");
      case ComputerAction.Wait a -> Map.of("millis",a.millis());
      default -> Map.of();
    };
  }
  static String history(ComputerAction action) {
    var fields=of(action);
    if(fields.isEmpty()) return "";
    return " " + new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(fields).toString();
  }
}
