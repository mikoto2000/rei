package dev.mikoto2000.rei.computer;

public record ComputerUseLoopResult(
    boolean completed,
    String stopReason,
    int actions,
    ComputerObservation lastObservation) {
}
