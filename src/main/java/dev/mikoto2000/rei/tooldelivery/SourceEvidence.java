package dev.mikoto2000.rei.tooldelivery;

/** Coordinates always refer to the specified immutable source version, never the current file. */
public record SourceEvidence(
    String path,
    String version,
    int startLine,
    int endLine,
    String selector,
    String sourceId,
    String rawResultRef) {}
