package dev.mikoto2000.rei.paper;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PaperContentService {
  private final PaperArtifactStore store;
  private final PaperHttpClient http;
  private final PaperExtractionService extraction;
  private final PaperProperties config;
  private final PaperRepository repository;
  private final List<PaperContentProvider> providers =
      List.of(new ArxivContentProvider(), new OpenAccessContentProvider());

  public PaperContentService(
      PaperArtifactStore store,
      PaperHttpClient http,
      PaperExtractionService extraction,
      PaperProperties config) {
    this(store, http, extraction, config, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public PaperContentService(
      PaperArtifactStore store,
      PaperHttpClient http,
      PaperExtractionService extraction,
      PaperProperties config,
      PaperRepository repository) {
    this.store = store;
    this.http = http;
    this.extraction = extraction;
    this.config = config;
    this.repository = repository;
  }

  public StructuredPaper content(Paper paper, PaperOperation op) {
    return PaperLocks.with(paper.id(), op, () -> contentLocked(paper, op));
  }

  private Optional<byte[]> read(Paper paper, String kind, String extension) {
    for (String id :
        repository == null ? List.of(paper.id()) : repository.artifactIds(paper.id())) {
      var bytes = store.read(id, kind, extension);
      if (bytes.isPresent()) return bytes;
    }
    return Optional.empty();
  }

  private StructuredPaper contentLocked(Paper paper, PaperOperation op) {
    op.check();
    if (repository != null && repository.find(paper.id()).isEmpty())
      throw new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文が見つかりません");
    List<String> warnings = new ArrayList<>();
    try {
      var cached = read(paper, "extracted", "json");
      if (cached.isPresent()) {
        var parsed =
            PaperJson.read(new String(cached.get(), StandardCharsets.UTF_8), StructuredPaper.class);
        validate(parsed);
        return parsed;
      }
    } catch (java.util.concurrent.CancellationException e) {
      throw e;
    } catch (RuntimeException e) {
      warnings.add("保存済み解析結果が破損しています。再解析します");
    }
    try {
      var pdf = read(paper, "originals", "pdf");
      org.slf4j.LoggerFactory.getLogger(getClass())
          .info("paper PDF paperId={} cache={}", paper.id(), pdf.isPresent() ? "hit" : "miss");
      if (pdf.isEmpty()) {
        for (var provider : providers) {
          var uri = provider.resolve(paper);
          if (uri.isPresent()) {
            pdf = Optional.of(http.get(uri.get(), "application/pdf", config.getMaxPdfBytes(), op));
            store.write(paper.id(), "originals", "pdf", pdf.get(), op);
            break;
          }
        }
      }
      if (pdf.isPresent()) {
        var parsed = extraction.extract(paper.title(), pdf.get(), op);
        store.write(
            paper.id(),
            "extracted",
            "json",
            PaperJson.write(parsed).getBytes(StandardCharsets.UTF_8),
            op);
        return parsed;
      }
    } catch (PaperException e) {
      warnings.add(e.code() + ": 本文を取得・解析できませんでした");
    }
    if (paper.abstractText() != null && !paper.abstractText().isBlank())
      return new StructuredPaper(
          paper.title(),
          StructuredPaper.Availability.ABSTRACT_ONLY,
          List.of(new PaperSection("Abstract", paper.abstractText(), 0, 0)),
          List.of(),
          warnings);
    return new StructuredPaper(
        paper.title(), StructuredPaper.Availability.METADATA_ONLY, List.of(), List.of(), warnings);
  }

  public void importPdf(Paper paper, byte[] pdf, PaperOperation op) {
    PaperLocks.with(
        paper.id(),
        op,
        () -> {
          importLocked(paper, pdf, op);
          return null;
        });
  }

  private void importLocked(Paper paper, byte[] pdf, PaperOperation op) {
    if (repository != null && repository.find(paper.id()).isEmpty())
      throw new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文が見つかりません");
    var parsed = extraction.extract(paper.title(), pdf, op);
    if (repository != null)
      repository.saveArtifact(paper.id(), "source", "revision", UUID.randomUUID().toString(), op);
    // Invalidate the prior extraction first. An interrupted replacement can then reparse the
    // original.
    store.delete(paper.id(), "extracted", "json");
    store.write(paper.id(), "originals", "pdf", pdf, op);
    store.write(
        paper.id(),
        "extracted",
        "json",
        PaperJson.write(parsed).getBytes(StandardCharsets.UTF_8),
        op);
  }

  private void validate(StructuredPaper p) {
    if (p.sections().size() > config.getMaxSections()
        || p.sections().stream().mapToLong(s -> s.text().length()).sum()
            > config.getMaxExtractedChars()) throw new IllegalArgumentException();
  }
}
