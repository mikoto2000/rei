package dev.mikoto2000.rei.core.stagnation;

/** Immutable explanation; source is a tool/step identifier, never raw tool output. */
public record ProgressEvidence(ProgressEvent kind, String description, String source) {}
