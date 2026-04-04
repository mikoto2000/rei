package dev.mikoto2000.rei.search;

import java.util.List;

import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchContext;

public record SearchKnowledgeResult(
    String query,
    List<VectorDocumentSearchResult> vectorResults,
    WebSearchContext webContext,
    String webSearchSkippedMessage) {
}
