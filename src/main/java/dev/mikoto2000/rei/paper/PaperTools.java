package dev.mikoto2000.rei.paper;

import java.util.Map;
import java.util.function.Supplier;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** 論文をモデルから呼び出せるツールとして提供する。 */
@Component
public class PaperTools {
  /** サービス。 */
  private final PaperResearchService service;

  /** 指定された値や利用先を使って、PaperToolsを初期化する。 */
  public PaperTools(PaperResearchService service) {
    this.service = service;
  }

  /** 利用先の制約に合わせて、指定された値を扱える形に整える。 */
  private Object safe(Supplier<Object> action) {
    try {
      service.library.enabled();
      return action.get();
    } catch (PaperException e) {
      return Map.of("success", false, "error", e.code(), "message", e.getMessage());
    } catch (RuntimeException e) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(e);
      return Map.of(
          "success",
          false,
          "error",
          PaperException.Code.LIBRARY_READ_FAILED,
          "message",
          "論文処理に失敗しました");
    }
  }

  /** 依頼に関連する情報を得るため、論文一覧を検索する。 */
  @Tool(
      description =
          "Search academic papers using OpenAlex, with Crossref fallback. Converts natural-language"
              + " paper requests to structured query/year/authors/venues/OA/sort/limit. Results are"
              + " automatically saved. References are session-local.")
  public Object searchPapers(PaperSearchQuery query, ToolContext context) {
    return safe(
        () -> {
          var r = service.search.search(query, PaperOperation.from(context));
          return Map.of("papers", PaperViews.listing(r.papers()), "warnings", r.warnings());
        });
  }

  /** 論文の調査と整理で参照する論文を返す。 */
  @Tool(
      description =
          "Get saved paper metadata by session result number or permanent UUID. enrich=true"
              + " supplements DOI metadata through Crossref. Never invent authors, DOI or year.")
  public Object getPaper(String reference, boolean enrich, ToolContext context) {
    return safe(
        () -> {
          var op = PaperOperation.from(context);
          var p = service.library.get(reference, op);
          return service.library.describe(enrich ? service.search.enrich(p, op) : p);
        });
  }

  /** 依頼に関連する情報を得るため、論文保管資料を検索する。 */
  @Tool(
      description =
          "Search previously saved/read papers in Paper Library by title, author, abstract, venue,"
              + " DOI, year or tags. Empty query lists papers.")
  public Object searchPaperLibrary(String query, int limit, ToolContext context) {
    return safe(
        () ->
            PaperViews.listing(service.library.search(query, limit, PaperOperation.from(context))));
  }

  /** 論文の調査と整理で参照する論文内容を返す。 */
  @Tool(
      description =
          "Download/parse a public paper if possible. Returns availability and section index only,"
              + " never the full text. Abstract-only must be disclosed.")
  public Object getPaperContent(String reference, ToolContext context) {
    return safe(
        () -> {
          var op = PaperOperation.from(context);
          return PaperViews.content(
              service.content.content(service.library.get(reference, op), op));
        });
  }

  /** 論文の調査と整理で参照する論文節を返す。 */
  @Tool(
      description =
          "Retrieve a single paper section by zero-based index from getPaperContent, with character"
              + " offset. Returns at most 4000 characters for working context.")
  public Object getPaperSection(String reference, int section, int offset, ToolContext context) {
    return safe(
        () -> {
          var op = PaperOperation.from(context);
          var p = service.content.content(service.library.get(reference, op), op);
          if (section < 0 || section >= p.sections().size())
            throw new PaperException(PaperException.Code.INVALID_QUERY, "セクション番号が不正です");
          var s = p.sections().get(section);
          if (offset < 0 || offset > s.text().length())
            throw new PaperException(PaperException.Code.INVALID_QUERY, "offset が不正です");
          int end = Math.min(s.text().length(), offset + 4000);
          return Map.of(
              "heading",
              s.heading(),
              "page",
              s.startPage(),
              "text",
              s.text().substring(offset, end),
              "nextOffset",
              end,
              "complete",
              end == s.text().length());
        });
  }

  /** 利用者が要点を把握できるよう、論文を要約する。 */
  @Tool(
      description =
          "Summarize a paper in Japanese with validated evidence. Modes QUICK/STANDARD/DETAILED."
              + " Default STANDARD. refresh bypasses saved version. Always disclose availability"
              + " and excerpt warnings.")
  public Object summarizePaper(
      String reference, PaperSummary.Mode mode, boolean refresh, ToolContext context) {
    return safe(
        () -> {
          var op = PaperOperation.from(context);
          var p = service.library.get(reference, op);
          var s = service.summary.summarize(p, mode, refresh, op);
          return Map.of(
              "paper",
              PaperViews.detail(p),
              "scope",
              PaperViews.scope(s.availability()),
              "summary",
              s);
        });
  }

  /** 利用者が内容を読めるよう、論文を翻訳する。 */
  @Tool(
      description =
          "Translate paper sections into Japanese; TECHNICAL default, or LITERAL/NATURAL. Empty"
              + " section means all. Persists complete translation, returns bounded preview. No"
              + " full text in conversation.")
  public Object translatePaper(
      String reference,
      PaperTranslation.Mode mode,
      String section,
      boolean refresh,
      ToolContext context) {
    return safe(
        () -> {
          var op = PaperOperation.from(context);
          return PaperViews.translation(
              service.translation.translate(
                  service.library.get(reference, op), mode, section, refresh, op));
        });
  }

  /** 論文の調査と整理で参照する論文翻訳文書の分割単位を返す。 */
  @Tool(
      description =
          "Read a saved Japanese translation chunk (zero-based index), at most 4000 characters from"
              + " offset. Use same mode and section as translatePaper.")
  public Object getPaperTranslationChunk(
      String reference,
      PaperTranslation.Mode mode,
      String section,
      int chunk,
      int offset,
      ToolContext context) {
    return safe(
        () -> {
          var op = PaperOperation.from(context);
          var t =
              service.translation.translate(
                  service.library.get(reference, op), mode, section, false, op);
          if (chunk < 0 || chunk >= t.chunks().size())
            throw new PaperException(PaperException.Code.INVALID_QUERY, "chunk が不正です");
          var c = t.chunks().get(chunk);
          if (offset < 0 || offset > c.content().length())
            throw new PaperException(PaperException.Code.INVALID_QUERY, "offset が不正です");
          int end = Math.min(c.content().length(), offset + 4000);
          return Map.of(
              "section",
              c.section(),
              "page",
              c.page(),
              "content",
              c.content().substring(offset, end),
              "nextOffset",
              end,
              "complete",
              end == c.content().length());
        });
  }
}
