package dev.mikoto2000.rei.paper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/** 論文内容のユースケースを実現する。 */
@Service
public class PaperContentService {
  private final PaperArtifactAccess.Read readArtifact;
  private final PaperArtifactAccess.Write writeArtifact;
  private final Consumer<String> deleteExtraction;

  /** HTTP通信。 */
  private final PaperHttpClient http;

  /** 論文から本文を抽出する窓口。 */
  @FunctionalInterface
  private interface ExtractPaper {
    StructuredPaper apply(String title, byte[] pdf, PaperOperation operation);
  }

  private final ExtractPaper extractPaper;

  /** 設定。 */
  private final Supplier<PaperSettings> settings;

  private final Function<String, List<String>> artifactIds;
  private final Predicate<String> paperExists;
  private final BiConsumer<String, PaperOperation> recordSourceRevision;

  /** 提供元一覧。 */
  private final List<PaperContentProvider> providers =
      List.of(new ArxivContentProvider(), new OpenAccessContentProvider());

  /** 論文の調査と整理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public PaperContentService(
      PaperArtifactStore store,
      PaperHttpClient http,
      PaperExtractionService extraction,
      PaperProperties config) {
    this(store, http, extraction, config, null);
  }

  /** 指定された値や利用先を使って、PaperContentServiceを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public PaperContentService(
      PaperArtifactStore store,
      PaperHttpClient http,
      PaperExtractionService extraction,
      PaperProperties config,
      PaperRepository repository) {
    this.readArtifact = (id, kind, extension) -> store.read(id, kind, extension);
    this.writeArtifact =
        (id, kind, extension, bytes, op) -> store.write(id, kind, extension, bytes, op);
    this.deleteExtraction = id -> store.delete(id, "extracted", "json");
    this.http = http;
    this.extractPaper = (title, pdf, operation) -> extraction.extract(title, pdf, operation);
    this.settings = () -> PaperSettings.capture(config);
    this.artifactIds = id -> repository == null ? List.of(id) : repository.artifactIds(id);
    this.paperExists = id -> repository == null || repository.find(id).isPresent();
    this.recordSourceRevision =
        (id, op) -> {
          if (repository != null)
            repository.saveArtifact(id, "source", "revision", UUID.randomUUID().toString(), op);
        };
  }

  /** 論文の調査と整理で用いる内容を返す。 */
  public StructuredPaper content(Paper paper, PaperOperation op) {
    return PaperLocks.with(paper.id(), op, () -> contentLocked(paper, op));
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private Optional<byte[]> read(Paper paper, String kind, String extension) {
    for (String id : artifactIds.apply(paper.id())) {
      var bytes = readArtifact.read(id, kind, extension);
      if (bytes.isPresent()) return bytes;
    }
    return Optional.empty();
  }

  /** 論文の調査と整理で用いる内容使用中を返す。 */
  private StructuredPaper contentLocked(Paper paper, PaperOperation op) {
    var config = settings.get();
    op.check();
    if (!paperExists.test(paper.id()))
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
            pdf = Optional.of(http.get(uri.get(), "application/pdf", config.maxPdfBytes(), op));
            writeArtifact.write(paper.id(), "originals", "pdf", pdf.get(), op);
            break;
          }
        }
      }
      if (pdf.isPresent()) {
        var parsed = extractPaper.apply(paper.title(), pdf.get(), op);
        writeArtifact.write(
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

  /** 論文の調査と整理に関する取り込みPDFを処理する。 */
  public void importPdf(Paper paper, byte[] pdf, PaperOperation op) {
    PaperLocks.with(
        paper.id(),
        op,
        () -> {
          importLocked(paper, pdf, op);
          return null;
        });
  }

  /** 論文の調査と整理に関する取り込み使用中を処理する。 */
  private void importLocked(Paper paper, byte[] pdf, PaperOperation op) {
    if (!paperExists.test(paper.id()))
      throw new PaperException(PaperException.Code.PAPER_NOT_FOUND, "論文が見つかりません");
    var parsed = extractPaper.apply(paper.title(), pdf, op);
    recordSourceRevision.accept(paper.id(), op);
    // Invalidate the prior extraction first. An interrupted replacement can then reparse the
    // original.
    deleteExtraction.accept(paper.id());
    writeArtifact.write(paper.id(), "originals", "pdf", pdf, op);
    writeArtifact.write(
        paper.id(),
        "extracted",
        "json",
        PaperJson.write(parsed).getBytes(StandardCharsets.UTF_8),
        op);
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  private void validate(StructuredPaper p) {
    var config = settings.get();
    if (p.sections().size() > config.maxSections()
        || p.sections().stream().mapToLong(s -> s.text().length()).sum()
            > config.maxExtractedChars()) throw new IllegalArgumentException();
  }
}
