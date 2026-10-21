package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
import dev.mikoto2000.rei.feed.FeedOpmlImportService;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.feed.command.FeedCommand;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** フィードコマンド一覧をWeb APIの操作として受け付ける。 */
@RestController
@ConditionalOnProperty(name = "rei.web.enabled", havingValue = "true")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/api/v1/feed/commands")
public final class FeedCommandsController {
  /** フィード一覧。 */
  private final FeedService feeds;

  /** 読み込む宣言。 */
  private final FeedOpmlImportService imports;

  /** 実行一覧。 */
  private final IntegrationRunSubmitService runs;

  /** 指定された値や利用先を使って、FeedCommandsControllerを初期化する。 */
  public FeedCommandsController(
      FeedService feeds, FeedOpmlImportService imports, IntegrationRunSubmitService runs) {
    this.feeds = feeds;
    this.imports = imports;
    this.runs = runs;
  }

  /**
   * Web APIによる操作で受け渡すテキストを表す。
   *
   * @param text テキスト
   */
  public record Text(String text) {}

  /**
   * Web APIによる操作で受け渡す取り込みを表す。
   *
   * @param path パス
   */
  public record Import(String path) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す追加を表す。
   *
   * @param url 参照先のURL
   * @param displayName 表示名前
   */
  public record Add(String url, String displayName) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す編集を表す。
   *
   * @param displayName 表示名前
   * @param enabled 有効状態
   */
  public record Edit(String displayName, Boolean enabled) implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す更新を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param feedId フィード識別子
   */
  public record Update(String projectId, String sessionId, Long feedId)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す要約を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param feedId フィード識別子
   */
  public record Summary(String projectId, String sessionId, Long feedId)
      implements StrictApiRequest {}

  /**
   * Web APIによる操作で受け渡す項目要約を表す。
   *
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param itemId 項目識別子
   */
  public record ItemSummary(String projectId, String sessionId, Long itemId)
      implements StrictApiRequest {}

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  @GetMapping
  public Text list() {
    return text(FeedCommand.reportList(feeds));
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  @PostMapping
  public Text add(@RequestBody Add request) {
    required(request.url());
    optional(request.displayName());
    return text(FeedCommand.reportAdd(feeds, request.url(), request.displayName()));
  }

  /** Web APIによる操作で用いる編集を返す。 */
  @PatchMapping("/{id}")
  public Text edit(@PathVariable long id, @RequestBody Edit request) {
    optional(request.displayName());
    return text(FeedCommand.reportEdit(feeds, id, request.displayName(), request.enabled()));
  }

  /** 指定された対象を削除する。 */
  @DeleteMapping("/{id}")
  public Text delete(@PathVariable long id) {
    return text(FeedCommand.reportDelete(feeds, id));
  }

  /** Web APIによる操作で用いる取り込みOPMLを返す。 */
  @PostMapping("/imports")
  public Text importOpml(@RequestBody Import request) {
    required(request.path());
    if (!java.nio.file.Path.of(request.path()).isAbsolute())
      throw new IllegalArgumentException("Absolute OPML path required");
    return text(FeedCommand.reportImport(imports, request.path()));
  }

  /** Web APIによる操作で用いる項目一覧を返す。 */
  @GetMapping("/items")
  public Text items(
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to,
      @RequestParam(defaultValue = "10") int limit) {
    return text(
        FeedCommand.reportItems(
            feeds,
            from == null ? null : OffsetDateTime.parse(from),
            to == null ? null : OffsetDateTime.parse(to),
            limit));
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  @PostMapping("/update")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> update(
      @RequestBody Update request) {
    required(request.projectId());
    return accepted(runs.updateFeeds(request.projectId(), request.sessionId(), request.feedId()));
  }

  /** Web APIによる操作で用いる要約を返す。 */
  @PostMapping("/summary")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> summary(
      @RequestBody Summary request) {
    required(request.projectId());
    if (request.feedId() != null) throw new IllegalArgumentException("Summary takes no feed ID");
    return accepted(runs.summarizeFeeds(request.projectId(), request.sessionId()));
  }

  /** 利用者が要点を把握できるよう、項目を要約する。 */
  @PostMapping("/items/summarize")
  public ResponseEntity<BackgroundRunController.AcceptedRunResponse> summarizeItem(
      @RequestBody ItemSummary request) {
    required(request.projectId());
    if (request.itemId() == null) throw new IllegalArgumentException("Item required");
    return accepted(
        runs.summarizeFeedItem(request.projectId(), request.sessionId(), request.itemId()));
  }

  /** Web APIによる操作に関する任意を処理する。 */
  private static void optional(String value) {
    if (value != null) required(value);
  }

  /** Web APIによる操作に関する必須を処理する。 */
  private static void required(String value) {
    if (value == null || value.isBlank() || value.length() > 16384 || value.indexOf('\0') >= 0)
      throw new IllegalArgumentException("Invalid feed argument");
  }

  /** 表示や受け渡しに用いるテキストを返す。 */
  private static Text text(String value) {
    return new Text(dev.mikoto2000.rei.event.CredentialRedactor.redact(value));
  }

  /** Web APIによる操作で用いる受付済みを返す。 */
  private static ResponseEntity<BackgroundRunController.AcceptedRunResponse> accepted(
      dev.mikoto2000.rei.core.chat.AgentRunContext run) {
    return ResponseEntity.accepted()
        .location(java.net.URI.create("/api/v1/runs/" + run.runId()))
        .body(new BackgroundRunController.AcceptedRunResponse(run.runId()));
  }
}
