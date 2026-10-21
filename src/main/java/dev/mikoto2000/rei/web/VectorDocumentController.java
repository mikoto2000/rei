package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.core.command.EmbedCommand;
import dev.mikoto2000.rei.vectordocument.AsyncVectorDocumentService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ベクトル文書をWeb APIの操作として受け付ける。
 *
 * <p>Named vector collection operations; explicit files only, never Backend glob expansion.
 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/vector-documents")
public final class VectorDocumentController {
  /** ベクトル一覧。 */
  private final VectorDocumentService vectors;

  /** バックグラウンド。 */
  private final AsyncVectorDocumentService background;

  /** 実行一覧。 */
  private dev.mikoto2000.rei.application.run.IntegrationRunSubmitService runs;

  /** Web APIによる操作で使用する実行一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRuns(dev.mikoto2000.rei.application.run.IntegrationRunSubmitService runs) {
    this.runs = runs;
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す保存済み文書を表す。
   *
   * @param documents 保存済み文書
   */
  public record Documents(List<String> documents) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡すIngestionを表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param documents 保存済み文書
   */
  public record Ingestion(String projectId, String sessionId, List<String> documents)
      implements StrictApiRequest {}

  /** Web APIによる操作で用いるingestを返す。 */
  @PostMapping("/ingestions")
  public org.springframework.http.ResponseEntity<BackgroundRunController.AcceptedRunResponse>
      ingest(@RequestBody Ingestion request) {
    if (request == null
        || request.documents() == null
        || request.documents().isEmpty()
        || request.documents().size() > 100)
      throw new IllegalArgumentException("Explicit documents required");
    required(request.projectId());
    optional(request.sessionId());
    request
        .documents()
        .forEach(
            path -> {
              required(path);
              if (!java.nio.file.Path.of(path).isAbsolute()
                  || path.indexOf('*') >= 0
                  || path.indexOf('?') >= 0
                  || path.indexOf('[') >= 0)
                throw new IllegalArgumentException("Expand file globs in the client");
            });
    var run =
        java.util.Objects.requireNonNull(runs, "Embedding runtime unavailable")
            .ingestVectors(
                request.projectId(), request.sessionId(), List.copyOf(request.documents()));
    return org.springframework.http.ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }

  /**
   * Web APIによる操作で受け渡す削除を表す。
   *
   * @param docId 文書の識別子
   * @param source 取得元
   */
  public record Deletion(String docId, String source) implements StrictApiRequest {}

  /** 指定された値や利用先を使って、VectorDocumentControllerを初期化する。 */
  public VectorDocumentController(
      VectorDocumentService vectors, AsyncVectorDocumentService background) {
    this.vectors = vectors;
    this.background = background;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public Text list() {
    return text(EmbedCommand.reportList(vectors));
  }

  /** 指定された検索条件に合う情報を探し、候補を返す。 */
  @GetMapping("/search")
  public Text search(
      @RequestParam String query,
      @RequestParam(required = false) Integer topK,
      @RequestParam(required = false) Double threshold,
      @RequestParam(required = false) String source) {
    required(query);
    optional(source);
    if (threshold != null && !Double.isFinite(threshold))
      throw new IllegalArgumentException("Invalid threshold");
    return text(EmbedCommand.reportSearch(vectors, query, topK, threshold, source));
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @PostMapping
  public Text add(@RequestBody Documents request) {
    if (request == null
        || request.documents() == null
        || request.documents().isEmpty()
        || request.documents().size() > 100)
      throw new IllegalArgumentException("Explicit documents required");
    request
        .documents()
        .forEach(
            path -> {
              required(path);
              if (path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0)
                throw new IllegalArgumentException("Expand file globs in the client");
            });
    var documents = List.copyOf(request.documents());
    background.addAsync(documents);
    return text("追加処理を開始: " + String.join(", ", documents));
  }

  /** 指定された対象を削除する。 */
  @PostMapping("/delete")
  public Text delete(@RequestBody Deletion request) {
    if (request == null) throw new IllegalArgumentException("Deletion required");
    optional(request.docId());
    optional(request.source());
    return text(EmbedCommand.reportDelete(vectors, request.docId(), request.source()));
  }

  /** Web APIによる操作に関する必須を処理する。 */
  private static void required(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Value required");
    optional(value);
  }

  /** Web APIによる操作に関する任意を処理する。 */
  private static void optional(String value) {
    if (value != null && (value.length() > 16384 || value.indexOf('\0') >= 0))
      throw new IllegalArgumentException("Invalid value");
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }
}
