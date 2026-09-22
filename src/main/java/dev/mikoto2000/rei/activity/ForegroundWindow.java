package dev.mikoto2000.rei.activity;

/** OS evidence, never supplied by Vision. */
public record ForegroundWindow(String processName, long processId, String windowTitle, String windowId) {}
