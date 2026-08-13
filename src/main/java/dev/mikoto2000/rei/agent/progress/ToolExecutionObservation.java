package dev.mikoto2000.rei.agent.progress;

public record ToolExecutionObservation(
    String toolName,
    String arguments,
    String result,
    boolean error) {
}
