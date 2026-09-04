package dev.mikoto2000.rei.computer;

public record ComputerWindow(
    String id,
    String title,
    String className,
    ComputerBounds bounds,
    boolean active,
    boolean focused) {
}
