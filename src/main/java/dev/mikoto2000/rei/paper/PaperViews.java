package dev.mikoto2000.rei.paper;

import java.util.*;

final class PaperViews {
  static String clip(String text, int limit) {
    return text == null ? null : text.length() <= limit ? text : text.substring(0, limit) + "…";
  }

  static Object listing(List<Paper> papers) {
    List<Object> result = new ArrayList<>();
    int n = 1;
    for (Paper p : papers)
      result.add(
          Map.of(
              "ref",
              n++,
              "id",
              p.id(),
              "title",
              clip(p.title() == null ? "" : p.title(), 300),
              "authors",
              p.authors().stream().limit(5).map(a -> clip(a, 100)).toList(),
              "year",
              p.publicationYear() == null ? "unknown" : p.publicationYear(),
              "venue",
              clip(p.venue() == null ? "" : p.venue(), 150),
              "doi",
              clip(p.doi() == null ? "" : p.doi(), 200),
              "citationCount",
              p.citationCount() == null ? "unknown" : p.citationCount()));
    return result;
  }

  static Object detail(Paper p) {
    return new Paper(
        p.id(),
        clip(p.title(), 500),
        p.authors().stream().limit(30).map(a -> clip(a, 100)).toList(),
        clip(p.abstractText(), 4000),
        p.publicationYear(),
        clip(p.venue(), 200),
        clip(p.doi(), 200),
        clip(p.arxivId(), 100),
        clip(p.openAlexId(), 200),
        p.citationCount(),
        p.openAccess(),
        clip(p.landingPageUrl(), 2048),
        clip(p.pdfUrl(), 2048),
        p.tags().stream().limit(20).map(t -> clip(t, 100)).toList(),
        clip(p.publishedAt(), 30),
        p.licenses().stream().limit(10).map(l -> clip(l, 512)).toList());
  }

  static Object content(StructuredPaper p) {
    List<Object> sections = new ArrayList<>();
    for (int i = 0; i < p.sections().size(); i++) {
      var s = p.sections().get(i);
      sections.add(
          Map.of(
              "index",
              i,
              "heading",
              s.heading(),
              "startPage",
              s.startPage(),
              "endPage",
              s.endPage(),
              "characters",
              s.text().length()));
    }
    return Map.of("availability", p.availability(), "sections", sections, "warnings", p.warnings());
  }

  static String scope(StructuredPaper.Availability a) {
    return switch (a) {
      case FULL_TEXT -> "全文から抽出したセクションを元にしています";
      case ABSTRACT_ONLY -> "Abstract のみを元にしています";
      case METADATA_ONLY -> "Metadata のみ（本文未取得）";
    };
  }

  static Object translation(PaperTranslation t) {
    return Map.of(
        "paperId",
        t.paperId(),
        "mode",
        t.mode(),
        "availability",
        t.availability(),
        "scope",
        scope(t.availability()),
        "chunks",
        t.chunks().size(),
        "preview",
        clip(t.chunks().isEmpty() ? "" : t.chunks().getFirst().content(), 4000),
        "message",
        "全文は Paper Library の翻訳 Artifact に保存済み。getPaperTranslationChunk で再取得できます",
        "warnings",
        t.warnings());
  }
}
