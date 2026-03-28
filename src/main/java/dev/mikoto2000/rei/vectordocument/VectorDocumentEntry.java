package dev.mikoto2000.rei.vectordocument;

public record VectorDocumentEntry(
    String docId,
    String source,
    int chunkCount,
    String ingestedAt) {
}
