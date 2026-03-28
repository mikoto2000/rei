package dev.mikoto2000.rei.vectordocument;

public record VectorDocumentSearchResult(
    String docId,
    String source,
    int chunkIndex,
    Double score,
    String snippet) {
}
