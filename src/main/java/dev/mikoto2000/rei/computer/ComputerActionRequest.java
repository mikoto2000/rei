package dev.mikoto2000.rei.computer;

public record ComputerActionRequest(
    String observationId,
    String elementId,
    ComputerActionType action,
    String text,
    String keyStroke,
    Integer wheelAmount) {

  public static ComputerActionRequest invoke(String observationId, String elementId) {
    return new ComputerActionRequest(observationId, elementId, ComputerActionType.INVOKE, null, null, null);
  }
}
