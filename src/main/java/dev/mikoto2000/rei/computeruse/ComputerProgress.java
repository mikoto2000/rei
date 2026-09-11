package dev.mikoto2000.rei.computeruse;
/** No screenshot or typed text is carried through progress events. */
public record ComputerProgress(int step, String phase, String action, String target,
    Integer x, Integer y, Double confidence, String reason) {}
