package dev.mikoto2000.rei.computeruse;
@FunctionalInterface public interface SafetyPolicy {
  boolean allows(ComputerAction action);
  static SafetyPolicy lowRiskOnly() { return action -> action.risk() == ComputerAction.Risk.LOW; }
  static SafetyPolicy fullAuto() {
    return action -> action.risk() == ComputerAction.Risk.LOW || action.risk() == ComputerAction.Risk.CONFIRM_REQUIRED;
  }
}
