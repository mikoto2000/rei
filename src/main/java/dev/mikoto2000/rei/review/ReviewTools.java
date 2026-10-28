package dev.mikoto2000.rei.review;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.contextbudget.RawToolResultStore;
import dev.mikoto2000.rei.core.contextbudget.ResultDelivery;
import dev.mikoto2000.rei.core.contextbudget.TokenEstimator;
import dev.mikoto2000.rei.review.ReviewCoverageLedger.Finding;
import dev.mikoto2000.rei.review.ReviewCoverageLedger.Job;
import dev.mikoto2000.rei.review.ReviewCoverageLedger.Range;
import dev.mikoto2000.rei.review.ReviewCoverageLedger.Scope;
import dev.mikoto2000.rei.review.ReviewCoverageLedger.Span;
import dev.mikoto2000.rei.review.ReviewCoverageLedger.Target;
import dev.mikoto2000.rei.storage.StorageDatabase;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;

/**
 * レビューをモデルから呼び出せるツールとして提供する。
 *
 * <p>Explicit review protocol. Reading never means delivery, and delivery never means reviewed.
 */
public final class ReviewTools {
  /**
   * 変更内容のレビューで受け渡すページを表す。
   *
   * @param reviewJobId レビュー実行する作業識別子
   * @param range 対象範囲
   * @param offset 開始位置
   * @param nextOffset 次開始位置
   * @param truncated 省略の有無
   * @param content 内容
   */
  public record Page(
      String reviewJobId,
      Range range,
      int offset,
      int nextOffset,
      boolean truncated,
      String content) {
    @com.fasterxml.jackson.annotation.JsonIgnore
    public dev.mikoto2000.rei.tooldelivery.SourceEvidence evidence() {
      return new dev.mikoto2000.rei.tooldelivery.SourceEvidence(
          range.path(),
          range.version(),
          range.span().startLine(),
          range.span().endLine(),
          null,
          "review:" + reviewJobId,
          null);
    }
  }

  /**
   * 変更内容のレビューで受け渡す受付記録を表す。
   *
   * @param job 実行する作業
   * @param range 対象範囲
   * @param serializedHash serializedハッシュ値
   */
  public record Receipt(String job, Range range, String serializedHash)
      implements dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.ReceiptValue {}

  /** 記録台帳。 */
  private final ReviewCoverageLedger ledger;

  /** 元の。 */
  private final RawToolResultStore raw;

  /** ファイル一覧。 */
  private final java.util.function.Supplier<Tools> files;

  /** 概算。 */
  private final TokenEstimator estimator;

  private final dev.mikoto2000.rei.durable.DurableTaskStore tasks;

  private java.util.function.Function<AgentRunContext, String> goalResolver = owner -> null;

  public void setGoalResolver(java.util.function.Function<AgentRunContext, String> resolver) {
    goalResolver = resolver;
  }

  /** 変更内容のレビューを利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ReviewTools(
      ReviewCoverageLedger ledger, RawToolResultStore raw, Tools files, TokenEstimator estimator) {
    this(ledger, raw, () -> files, estimator);
  }

  /** 指定された値や利用先を使って、ReviewToolsを初期化する。 */
  public ReviewTools(
      ReviewCoverageLedger ledger,
      RawToolResultStore raw,
      java.util.function.Supplier<Tools> files,
      TokenEstimator estimator) {
    this.ledger = ledger;
    this.raw = raw;
    this.files = files;
    this.estimator = estimator;
    this.tasks = ledger.durableTasks();
  }

  private static dev.mikoto2000.rei.durable.DurableTaskStore.Scope taskScope(AgentRunContext owner) {
    scope(owner);
    return new dev.mikoto2000.rei.durable.DurableTaskStore.Scope(
        owner.projectId(), owner.conversationId(), owner.projectRoot().toString());
  }

  @Tool(description = "Begin a durable whole-repository review. COMMIT captures the immutable HEAD tree; WORKTREE preserves current contents including uncommitted files. Omitted mode prefers COMMIT at a Git root. Exclusions are explicit glob patterns with recorded reasons. Continue scanReviewSnapshot until SEALED; enumeration and processing are distinct. The owning Run and Goal cannot finish while required work remains.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Task beginRepositoryReview(
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String snapshotMode,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) List<String> excludes,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxFiles,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Long maxBytes) throws java.io.IOException {
    var owner = AgentRunScope.current();
    var capturedScope = taskScope(owner);
    String mode = snapshotMode == null ? "AUTO" : snapshotMode;
    String reference = "";
    if (!mode.equals("WORKTREE")) {
      try {
        reference = dev.mikoto2000.rei.durable.GitSnapshotSource.head(owner.projectRoot());
        if (mode.equals("AUTO")) mode = "COMMIT";
      } catch (java.io.IOException unavailable) {
        if (!mode.equals("AUTO")) throw unavailable;
        mode = "WORKTREE";
      }
    }
    return tasks.beginForRun(capturedScope, owner.runId(), goalResolver.apply(owner), mode, reference,
        excludes == null ? List.of("target/**", "node_modules/**", ".rei/**", ".worktrees/**") : excludes,
        maxFiles == null ? 100000 : maxFiles, maxBytes == null ? 2L * 1024 * 1024 * 1024 : maxBytes);
  }

  @Tool(description = "Capture the next bounded page of a durable review Snapshot. maxEntries defaults to 128 and is 1..1000. Continue until enumeration is SEALED. FAILED includes an explicit error or capacity reason; never claim complete enumeration in that state.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Task scanReviewSnapshot(String taskId,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxEntries) {
    return new dev.mikoto2000.rei.durable.SnapshotEnumerator(tasks)
        .step(taskScope(AgentRunScope.current()), taskId, maxEntries == null ? 128 : maxEntries);
  }

  @Tool(description = "Inspect durable review identity, frozen Snapshot ID, enumeration status, errors, exclusion count and unscanned directories. SEALED only proves target enumeration, not completed review.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Task reviewTaskStatus(String taskId) {
    return tasks.status(taskScope(AgentRunScope.current()), taskId);
  }

  @Tool(description = "Page frozen review targets, exclusions and source errors. Pass nextCursor unchanged as afterPath; null starts at the beginning. limit is 1..1000, default 100. Reading a page never completes work.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Page<dev.mikoto2000.rei.durable.DurableTaskStore.FileEntry> listReviewTargets(
      String taskId,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String afterPath,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer limit) {
    return tasks.files(taskScope(AgentRunScope.current()), taskId, afterPath, limit == null ? 100 : limit);
  }

  /** 変更内容のレビューで用いる対象範囲を返す。 */
  public static Scope scope(AgentRunContext owner) {
    if (owner == null || owner.projectId() == null)
      throw new IllegalStateException("A Project run is required");
    return new Scope(owner.projectId(), owner.conversationId(), owner.runId());
  }

  /** 変更内容のレビューで用いる開始レビューを返す。 */
  @Tool(
      description =
          "Begin a coverage-verified review of explicit Project text/PDF files. Criteria and criteriaVersion are required. Read pages with readReviewFile, then submit one structured result for each delivered range. Reading alone never completes a review.")
  public Job beginReview(List<String> paths, String criteria, String criteriaVersion)
      throws java.io.IOException {
    var owner = AgentRunScope.current();
    var scope = scope(owner);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (paths == null || paths.isEmpty() || paths.size() > 64)
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    var targets = new ArrayList<Target>();
    int bytes = 0;
    for (var path : paths) {
      var snapshot = files.get().reviewSnapshot(path);
      bytes += snapshot.text().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 8 * 1024 * 1024)
        throw new IllegalArgumentException("Review snapshot budget exceeded");
      var end = position(snapshot.text(), snapshot.text().length());
      targets.add(
          new Target(
              snapshot.path(),
              snapshot.version(),
              end[0],
              List.of(new Span(0, snapshot.text().length(), 1, end[0], 0, end[1]))));
    }
    return ledger.create(scope, criteria, criteriaVersion, targets);
  }

  /** 変更内容のレビューで用いる再開レビューを返す。 */
  @Tool(
      description =
          "Rebuild a STALE_VERSION review job from the current versions of its original files. Preserves historical findings, marks old-version ranges stale and starts the changed files again; never reuses old-version reviews.")
  public Job restartReview(String reviewJobId) throws java.io.IOException {
    var scope = scope(AgentRunScope.current());
    var old = ledger.get(reviewJobId, scope);
    var targets = new ArrayList<Target>();
    for (var target : old.targets()) {
      var snapshot = files.get().reviewSnapshot(target.path());
      var end = position(snapshot.text(), snapshot.text().length());
      targets.add(
          new Target(
              snapshot.path(),
              snapshot.version(),
              end[0],
              List.of(new Span(0, snapshot.text().length(), 1, end[0], 0, end[1]))));
    }
    return ledger.rebuild(reviewJobId, scope, targets);
  }

  /** 必要な内容を参照できるよう、レビューファイルを読み取る。 */
  @ResultDelivery(ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      description =
          "Read an exact decoded review page. Pass reviewJobId, path, expectedVersion, UTF-16 offset, and tokenBudget 256..2048. Start at offset 0 and follow nextOffset while truncated. A version change invalidates the job. Preserve each returned range unchanged in submitReviewResult.")
  public Page readReviewFile(
      String reviewJobId, String path, String expectedVersion, int offset, int tokenBudget)
      throws java.io.IOException {
    var owner = AgentRunScope.current();
    var scope = scope(owner);
    var job = ledger.get(reviewJobId, scope);
    var target =
        job.targets().stream()
            .filter(t -> t.path().equals(path))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("REVIEW_RESULT_INVALID"));
    if (!target.version().equals(expectedVersion)) throw new IllegalStateException("STALE_VERSION");
    if (tokenBudget < 256 || tokenBudget > 2048)
      throw new IllegalArgumentException("CONTEXT_BUDGET_INSUFFICIENT");
    var snapshot = files.get().reviewSnapshot(path);
    if (!target.version().equals(snapshot.version())) {
      ledger.invalidate(reviewJobId, scope, path, snapshot.version());
      throw new IllegalStateException("STALE_VERSION");
    }
    String source = snapshot.text();
    if (offset < 0
        || offset > source.length()
        || offset > 0
            && offset < source.length()
            && Character.isHighSurrogate(source.charAt(offset - 1))
            && Character.isLowSurrogate(source.charAt(offset)))
      throw new IllegalArgumentException("INVALID_CURSOR");
    int maxEnd = Math.min(source.length(), offset + tokenBudget * 4);
    if (maxEnd > offset
        && maxEnd < source.length()
        && Character.isLowSurrogate(source.charAt(maxEnd))) maxEnd--;
    int low = 0, high = source.codePointCount(offset, maxEnd);
    while (low < high) {
      int middle = low + (high - low + 1) / 2;
      var candidate =
          page(reviewJobId, target, source, offset, source.offsetByCodePoints(offset, middle));
      if (estimate(candidate) <= tokenBudget) low = middle;
      else high = middle - 1;
    }
    var page = page(reviewJobId, target, source, offset, source.offsetByCodePoints(offset, low));
    if (estimate(page) > tokenBudget || low == 0 && offset < source.length())
      throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
    ledger.fetched(reviewJobId, scope, page.range());
    return page;
  }

  /** 依頼を実行経路へ渡すため、レビュー結果を受け付ける。 */
  @Tool(
      description =
          "Submit a structured review for exactly one delivered range returned by readReviewFile. Provide assessment even if there are no findings. Findings must cite exact source evidence inside the range. Undelivered, changed-version, malformed or invented ranges are rejected.")
  public Job submitReviewResult(
      String reviewJobId, Range range, String assessment, List<Finding> findings)
      throws java.io.IOException {
    var owner = AgentRunScope.current();
    var scope = scope(owner);
    var job = reviewStatus(reviewJobId);
    if ("STALE_VERSION".equals(job.status())) throw new IllegalStateException("STALE_VERSION");
    var source = files.get().reviewSnapshot(range.path());
    if (!range.version().equals(source.version())
        || range.span().end() > source.text().length()
        || !hash(source.text().substring(range.span().start(), range.span().end()))
            .equals(range.contentHash()))
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    if (findings == null) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    for (var finding : findings) {
      if (!boundary(source.text(), finding.location().start())
          || !boundary(source.text(), finding.location().end()))
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      if (finding.location().start() < range.span().start()
          || finding.location().end() > range.span().end()
          || finding.location().end() > source.text().length())
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      int[] from = position(source.text(), finding.location().start()),
          to = position(source.text(), finding.location().end());
      if (finding.location().startLine() != from[0]
          || finding.location().endLine() != to[0]
          || finding.location().startOffset() != from[1]
          || finding.location().endOffset() != to[1])
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      if (finding.location().start() < range.span().start()
          || finding.location().end() > range.span().end()
          || !source
              .text()
              .substring(finding.location().start(), finding.location().end())
              .equals(finding.evidence()))
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    }
    return ledger.reviewed(reviewJobId, scope, range, assessment, findings);
  }

  /** 変更内容のレビューで用いるレビュー状態を返す。 */
  @Tool(
      description =
          "Inspect persisted review coverage and findings. Revalidates each current source version. COMPLETE only means verified range coverage; semantic review quality requires human judgment. Unreviewed/stale/failed ranges remain visible.")
  public Job reviewStatus(String reviewJobId) throws java.io.IOException {
    var scope = scope(AgentRunScope.current());
    var job = ledger.get(reviewJobId, scope);
    for (var target : job.targets()) {
      Tools.ReviewSnapshot snapshot;
      try {
        snapshot = files.get().reviewSnapshot(target.path());
      } catch (java.io.IOException missing) {
        job = ledger.invalidate(reviewJobId, scope, target.path(), "unavailable");
        continue;
      }
      if (!target.version().equals(snapshot.version()))
        job = ledger.invalidate(reviewJobId, scope, target.path(), snapshot.version());
    }
    return job;
  }

  /**
   * 変更内容のレビューに関する確認済みを処理する。
   *
   * <p>Called only after a model request containing these exact pages has successfully completed.
   */
  public void confirmed(AgentRunContext owner, List<Page> pages) {
    var grouped = new LinkedHashMap<String, List<Range>>();
    for (var page : pages) {
      if (!hash(page.content()).equals(page.range().contentHash()))
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      grouped.computeIfAbsent(page.reviewJobId(), ignored -> new ArrayList<>()).add(page.range());
    }
    grouped.forEach((job, ranges) -> ledger.delivered(job, scope(owner), ranges));
  }

  /** 受信した内容を利用できるよう、ページを復元する。 */
  private static Page decodePage(String result) throws java.io.IOException {
    var tree = StorageDatabase.JSON.readTree(result);
    if (tree.has("output")) {
      if (!tree.path("output").isTextual()
          || tree.path("truncated").asBoolean(true)
          || !hash(tree.path("output").asText()).equals(tree.path("outputSha256").asText()))
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      result = tree.path("output").asText();
    }
    var page = StorageDatabase.JSON.readValue(result, Page.class);
    if (page.reviewJobId() == null
        || page.range() == null
        || page.content() == null
        || !hash(page.content()).equals(page.range().contentHash()))
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    return page;
  }

  /** 変更内容のレビューで用いる受付記録を返す。 */
  public static Receipt receipt(String result) {
    try {
      var page = decodePage(result);
      return new Receipt(page.reviewJobId(), page.range(), hash(result));
    } catch (java.io.IOException invalid) {
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID", invalid);
    }
  }

  /**
   * 変更内容のレビューで受け渡す待機中を表す。
   *
   * @param path パス
   * @param version 形式のバージョン
   * @param start 開始
   * @param end 終了
   * @param startLine 開始行
   * @param startOffset 開始開始位置
   * @param state 状態
   */
  public record Pending(
      String path,
      String version,
      int start,
      int end,
      int startLine,
      int startOffset,
      String state) {}

  /** 変更内容のレビューで用いるレビュー待機中を返す。 */
  @Tool(
      description =
          "Return missing review spans with exact source version and next character offset. Stale, undelivered and unreviewed spans remain pending. Use reviewStatus for preserved findings and restartReview for changed sources.")
  public List<Pending> reviewPending(String reviewJobId) throws java.io.IOException {
    var job = reviewStatus(reviewJobId);
    var pending = new ArrayList<Pending>();
    for (var target : job.targets())
      for (var required : target.required()) {
        var complete =
            job.ranges().stream()
                .filter(
                    e ->
                        e.reviewed()
                            && e.delivered()
                            && !e.stale()
                            && e.range().path().equals(target.path())
                            && e.range().version().equals(target.version()))
                .map(e -> e.range().span())
                .sorted(Comparator.comparingInt(Span::start))
                .toList();
        int cursor = required.start();
        for (var span : complete) {
          if (span.end() <= cursor || span.start() >= required.end()) continue;
          if (span.start() > cursor)
            pending.add(
                pending(target, cursor, Math.min(span.start(), required.end()), job.status()));
          cursor = Math.max(cursor, Math.min(span.end(), required.end()));
        }
        if (cursor < required.end() || required.start() == required.end() && complete.isEmpty())
          pending.add(pending(target, cursor, required.end(), job.status()));
      }
    return List.copyOf(pending);
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  private Pending pending(Target target, int start, int end, String state)
      throws java.io.IOException {
    int[] position = {1, 0};
    try {
      var text = files.get().reviewSnapshot(target.path()).text();
      if (start <= text.length()) position = position(text, start);
    } catch (java.io.IOException unavailable) {
      /* Old-version coordinates remain visible on unavailable source. */
    }
    return new Pending(
        target.path(), target.version(), start, end, position[0], position[1], state);
  }

  /** 変更内容のレビューに関する確認済みプロンプトを処理する。 */
  public void confirmedPrompt(
      org.springframework.ai.chat.prompt.Prompt prompt, AgentRunContext owner) {
    var pages = new ArrayList<Page>();
    for (var message : prompt.getInstructions())
      if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool
          && tool.getMetadata().get("rei.reviewReceipts") instanceof Map<?, ?> receipts) {
        for (var response : tool.getResponses())
          if (receipts.get(response.id())
                  instanceof dev.mikoto2000.rei.tooldelivery.ToolResultMetadata.ReceiptValue receipt
              && hash(response.responseData()).equals(receipt.serializedHash())) {
            try {
              pages.add(decodePage(response.responseData()));
            } catch (java.io.IOException invalid) {
              throw new IllegalArgumentException("REVIEW_RESULT_INVALID", invalid);
            }
          }
      }
    if (!pages.isEmpty()) confirmed(owner, pages);
  }

  /** 変更内容のレビューで用いるページを返す。 */
  private Page page(String job, Target target, String source, int start, int end) {
    int[] from = position(source, start), to = position(source, end);
    String body = source.substring(start, end);
    return new Page(
        job,
        new Range(
            target.path(),
            target.version(),
            new Span(start, end, from[0], to[0], from[1], to[1]),
            hash(body)),
        start,
        end,
        end < source.length(),
        body);
  }

  /** 利用量や必要量を判断するため、レビューツール一覧を概算する。 */
  private int estimate(Page page) {
    try {
      String wire = StorageDatabase.JSON.writeValueAsString(page);
      var owner = AgentRunScope.current();
      if (owner != null && owner.conversationId().startsWith("subagent:"))
        wire =
            StorageDatabase.JSON.writeValueAsString(
                Map.of(
                    "evidenceId",
                    "00000000-0000-0000-0000-000000000000",
                    "tool",
                    "readReviewFile",
                    "inputSha256",
                    "0".repeat(64),
                    "outputSha256",
                    "0".repeat(64),
                    "output",
                    wire,
                    "truncated",
                    false));
      return estimator.text(wire);
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 変更内容のレビューにおいて、境界に該当するか判定する。 */
  private static boolean boundary(String source, int offset) {
    return offset >= 0
        && offset <= source.length()
        && (offset == 0
            || offset == source.length()
            || !Character.isHighSurrogate(source.charAt(offset - 1))
            || !Character.isLowSurrogate(source.charAt(offset)));
  }

  /** 変更内容のレビューで用いる位置を返す。 */
  private static int[] position(String source, int offset) {
    int line = 1, beginning = 0;
    for (int i = 0; i < offset; i++)
      if (source.charAt(i) == '\n') {
        line++;
        beginning = i + 1;
      }
    return new int[] {line, offset - beginning};
  }

  /** 条件を満たさない操作を拒否するため、完了の前提条件を確認する。 */
  public void requireComplete(AgentRunContext owner) {
    String cursor = null;
    do {
      var page = tasks.forRun(taskScope(owner), owner.runId(), cursor, 100);
      for (var task : page.entries())
        if (!tasks.verify(taskScope(owner), task.id()).complete())
          throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
              dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.REVIEW_PARTIAL);
      cursor = page.nextCursor();
    } while (cursor != null);
    for (var id : ledger.jobs(scope(owner)))
      try {
        if (!reviewStatus(id).status().equals("COMPLETE"))
          throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
              dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.REVIEW_PARTIAL);
      } catch (java.io.IOException error) {
        throw new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(
            dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.REVIEW_PARTIAL);
      }
  }

  /** 終了時の状態を確定するため、実行を完了させる。 */
  public void finishRun(
      AgentRunContext owner, boolean cancelled, boolean failed, AgentRunContext parent) {
    if (owner == null || owner.projectId() == null) return;
    var scope = scope(owner);
    for (var id : ledger.jobs(scope)) {
      var job = ledger.get(id, scope);
      if (!job.status().equals("COMPLETE") && (cancelled || failed))
        ledger.stop(id, scope, cancelled);
      if (parent != null
          && parent.projectId() != null
          && parent.projectId().equals(owner.projectId())
          && job.owner().equals(scope)) ledger.grant(id, scope, scope(parent));
    }
    raw.clearRun(owner.runId());
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  public static String hash(String content) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(content.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
