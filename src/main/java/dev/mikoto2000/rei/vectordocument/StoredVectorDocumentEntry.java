package dev.mikoto2000.rei.vectordocument;

import java.util.List;

record StoredVectorDocumentEntry(
    String docId,
    String source,
    int chunkCount,
    String ingestedAt,
    List<String> chunkIds) {

  VectorDocumentEntry toEntry() {
    return new VectorDocumentEntry(docId, source, chunkCount, ingestedAt);
  }
}
