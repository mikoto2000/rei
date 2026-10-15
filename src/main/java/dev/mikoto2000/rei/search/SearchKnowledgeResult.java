package dev.mikoto2000.rei.search;

import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import java.util.List;

public record SearchKnowledgeResult(
    String query,
    List<VectorDocumentSearchResult> vectorResults,
    WebSearchContext webContext,
    String webSearchSkippedMessage) {}
