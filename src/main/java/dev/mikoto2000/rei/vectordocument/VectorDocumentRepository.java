package dev.mikoto2000.rei.vectordocument;

import java.util.List;

import org.springframework.ai.document.Document;

public interface VectorDocumentRepository {

  VectorDocumentEntry replaceBySource(String docId, String source, String ingestedAt, List<Document> documents);

  List<VectorDocumentEntry> list();

  boolean deleteByDocId(String docId);

  int deleteBySource(String source);
}
