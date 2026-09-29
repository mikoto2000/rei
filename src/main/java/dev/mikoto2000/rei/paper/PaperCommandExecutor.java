package dev.mikoto2000.rei.paper;

import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class PaperCommandExecutor {
  private final PaperResearchService service;
  private final PaperProperties config;

  public PaperCommandExecutor(PaperResearchService service, PaperProperties config) {
    this.service = service;
    this.config = config;
  }

  public static boolean accepts(String text) {
    return text != null && (text.equals("/paper") || text.startsWith("/paper "));
  }

  public String execute(String text, PaperOperation op) {
    try {
      service.library.enabled();
      var words = new dev.mikoto2000.rei.core.command.UserInputParser().split(text);
      var r = PaperCommandRequest.parse(java.util.Arrays.copyOfRange(words, 1, words.length));
      Object result =
          switch (r.action) {
            case "search" -> {
              var found = service.search.search(r.query(config.getDefaultLimit()), op);
              yield Map.of(
                  "papers", PaperViews.listing(found.papers()), "warnings", found.warnings());
            }
            case "show" -> {
              var p = service.library.get(r.args.getFirst(), op);
              try {
                p = service.search.enrich(p, op);
                yield service.library.describe(p);
              } catch (PaperException e) {
                yield Map.of(
                    "paper", PaperViews.detail(p), "warning", "Crossref metadata: " + e.code());
              }
            }
            case "summarize" -> {
              var p = service.library.get(r.args.getFirst(), op);
              var s =
                  service.summary.summarize(
                      p,
                      r.quick
                          ? PaperSummary.Mode.QUICK
                          : r.detailed
                              ? PaperSummary.Mode.DETAILED
                              : config.getDefaultSummaryMode(),
                      r.refresh,
                      op);
              yield Map.of(
                  "paper",
                  PaperViews.detail(p),
                  "scope",
                  PaperViews.scope(s.availability()),
                  "summary",
                  s);
            }
            case "translate" -> {
              var p = service.library.get(r.args.getFirst(), op);
              yield PaperViews.translation(
                  service.translation.translate(
                      p,
                      r.literal
                          ? PaperTranslation.Mode.LITERAL
                          : r.natural
                              ? PaperTranslation.Mode.NATURAL
                              : r.technical
                                  ? PaperTranslation.Mode.TECHNICAL
                                  : config.getDefaultTranslationMode(),
                      r.section,
                      r.refresh,
                      op));
            }
            case "library" -> library(r, op);
            case "import" -> {
              if (op.execution() != null
                  && op.execution().runContext() != null
                  && op.execution().runContext().requestSource()
                      != dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.SHELL)
                throw new PaperException(
                    PaperException.Code.INVALID_QUERY, "PDF import はローカル Shell 専用です");
              var path = java.nio.file.Path.of(r.args.get(1));
              if (!path.isAbsolute()
                  && op.execution() != null
                  && op.execution().runContext() != null)
                path = op.execution().runContext().projectRoot().resolve(path).normalize();
              try (var stream = java.nio.file.Files.newInputStream(path)) {
                var pdf = stream.readNBytes(config.getMaxPdfBytes() + 1);
                service.content.importPdf(service.library.get(r.args.getFirst(), op), pdf, op);
              } catch (java.io.IOException e) {
                throw new PaperException(
                    PaperException.Code.PDF_PARSE_FAILED, "ユーザー提供 PDF を読めません", e);
              }
              yield Map.of("imported", true);
            }
            default -> throw new PaperException(PaperException.Code.INVALID_QUERY, "不明なコマンド");
          };
      return PaperJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result);
    } catch (PaperException e) {
      return e.code() + ": " + e.getMessage();
    } catch (RuntimeException e) {
      dev.mikoto2000.rei.core.chat.RunCancellation.propagate(e);
      return PaperException.Code.LIBRARY_READ_FAILED + ": 論文処理に失敗しました";
    }
  }

  private Object library(PaperCommandRequest r, PaperOperation op) {
    if (r.args.isEmpty())
      return PaperViews.listing(
          service.library.search(
              "", r.limit == null ? config.getMaxLibrarySearchResults() : r.limit, op));
    return switch (r.args.getFirst()) {
      case "search" ->
          PaperViews.listing(
              service.library.search(
                  String.join(" ", r.args.subList(1, r.args.size())),
                  r.limit == null ? config.getMaxLibrarySearchResults() : r.limit,
                  op));
      case "show" -> service.library.describe(service.library.get(r.args.get(1), op));
      case "remove", "purge" -> {
        service.library.remove(r.args.get(1), r.args.getFirst().equals("purge"), op);
        yield Map.of("action", r.args.getFirst(), "id", r.args.get(1));
      }
      default -> throw new PaperException(PaperException.Code.INVALID_QUERY, "不明な Library コマンド");
    };
  }
}
