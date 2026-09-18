package dev.mikoto2000.rei.subagent;

/** JSON Pointer and a value-free diagnostic, safe to return to the parent. */
public record ValidationError(String path, String message) { }
