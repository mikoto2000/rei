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

  private static dev.mikoto2000.rei.durable.DurableTaskStore.Scope taskScope(
      AgentRunContext owner) {
    scope(owner);
    return new dev.mikoto2000.rei.durable.DurableTaskStore.Scope(
        owner.projectId(), owner.conversationId(), owner.projectRoot().toString());
  }

  @Tool(
      description =
          "Begin a durable whole-repository review. COMMIT captures the immutable HEAD tree; WORKTREE preserves current contents including uncommitted files. Omitted mode prefers COMMIT at a Git root. Exclusions are explicit glob patterns with recorded reasons. Continue scanReviewSnapshot until SEALED; enumeration and processing are distinct. The owning Run and Goal cannot finish while required work remains.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Task beginRepositoryReview(
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String snapshotMode,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) List<String> excludes,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxFiles,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Long maxBytes)
      throws java.io.IOException {
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
    return tasks.beginForRun(
        capturedScope,
        owner.runId(),
        ledger.goalForTask(capturedScope, owner.runId()),
        mode,
        reference,
        excludes == null
            ? List.of("target/**", "node_modules/**", ".rei/**", ".worktrees/**")
            : excludes,
        maxFiles == null ? 100000 : maxFiles,
        maxBytes == null ? 2L * 1024 * 1024 * 1024 : maxBytes);
  }

  @Tool(
      description =
          "Capture the next bounded page of a durable review Snapshot. maxEntries defaults to 128 and is 1..1000. Continue until enumeration is SEALED. FAILED includes an explicit error or capacity reason; never claim complete enumeration in that state.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Task scanReviewSnapshot(
      String taskId,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer maxEntries) {
    return new dev.mikoto2000.rei.durable.SnapshotEnumerator(tasks)
        .step(taskScope(AgentRunScope.current()), taskId, maxEntries == null ? 128 : maxEntries);
  }

  @Tool(
      description =
          "Inspect durable review identity, frozen Snapshot ID, enumeration status, errors, exclusion count and unscanned directories. SEALED only proves target enumeration, not completed review.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Task reviewTaskStatus(String taskId) {
    return tasks.status(taskScope(AgentRunScope.current()), taskId);
  }

  @Tool(
      description =
          "Discover durable repository review Tasks owned by this Project, session and root, even when Checkpoints are missing. Pass nextCursor as afterTask. limit 1..1000, default 100.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Page<
          dev.mikoto2000.rei.durable.DurableTaskStore.Task>
      listReviewTasks(
          @org.springframework.ai.tool.annotation.ToolParam(required = false) String afterTask,
          @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer limit) {
    return tasks.tasks(taskScope(AgentRunScope.current()), afterTask, limit == null ? 100 : limit);
  }

  @Tool(
      description =
          "Explicitly resume a durable review Task in this Run without a Checkpoint. Preserves results, recovers unfinished read-only review work and retires old Run authority. Cancelled or failed Tasks cannot be resumed implicitly.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Task resumeRepositoryReview(String taskId) {
    var owner = AgentRunScope.current();
    return workItems().resume(taskScope(owner), taskId, owner.runId());
  }

  private dev.mikoto2000.rei.durable.WorkItemStore workItems() {
    return new dev.mikoto2000.rei.durable.WorkItemStore(tasks);
  }

  @Tool(
      description =
          "Claim one pending frozen review range. tokenBudget 256..2048 includes serialized source metadata. Already committed ranges are never returned. Read with readReviewWorkItem and then submitReviewWorkItem; a claim is not review completion.")
  public dev.mikoto2000.rei.durable.WorkItemStore.Claim claimReviewWorkItem(
      String taskId, int tokenBudget) {
    if (tokenBudget < 256 || tokenBudget > 2048)
      throw new IllegalArgumentException("INVALID_TOKEN_BUDGET");
    var owner = AgentRunScope.current();
    return workItems()
        .claim(
            taskScope(owner),
            taskId,
            owner.runId(),
            (item, source) -> {
              int low = item.start(), high = Math.min(item.end(), item.start() + tokenBudget * 4);
              while (low < high) {
                int middle = low + (high - low + 1) / 2;
                if (estimate(durablePage(item, "0".repeat(36), source, middle)) <= tokenBudget)
                  low = middle;
                else high = middle - 1;
              }
              if (low > item.start()
                  && low < source.length()
                  && Character.isHighSurrogate(source.charAt(low - 1))
                  && Character.isLowSurrogate(source.charAt(low))) low--;
              if (low == item.start() && low < item.end()
                  || estimate(durablePage(item, "0".repeat(36), source, low)) > tokenBudget)
                throw new IllegalArgumentException("REVIEW_PAGE_BUDGET_TOO_SMALL");
              return low;
            });
  }

  @ResultDelivery(ResultDelivery.Kind.FILE_CONTENT)
  @Tool(
      description =
          "Read the exact frozen range of the current claim. Reading does not mark it delivered; trusted host model-delivery confirmation is required before submitReviewWorkItem.")
  public Page readReviewWorkItem(String workItemId, String attempt) {
    var owner = AgentRunScope.current();
    var work = workItems();
    var claim = work.current(taskScope(owner), workItemId, owner.runId(), attempt);
    if (!claim.item().state().equals("RUNNING"))
      throw new IllegalArgumentException("WORK_NOT_RUNNING");
    return durablePage(
        claim.item(), attempt, work.source(taskScope(owner), claim.item()), claim.item().end());
  }

  @Tool(
      description =
          "Commit an assessment and optional structured findings for a delivered frozen claim. Findings use absolute UTF-16 start/end and exact evidence quotes. Saves atomically, rejects conflicting replay, and never equates reading with reviewing.")
  public dev.mikoto2000.rei.durable.WorkItemStore.Result submitReviewWorkItem(
      String workItemId,
      String attempt,
      String assessment,
      List<dev.mikoto2000.rei.durable.WorkItemStore.Finding> findings) {
    var owner = AgentRunScope.current();
    var work = workItems();
    return work.commit(
        taskScope(owner),
        work.current(taskScope(owner), workItemId, owner.runId(), attempt),
        assessment,
        findings);
  }

  private Page durablePage(
      dev.mikoto2000.rei.durable.WorkItemStore.Item item, String attempt, String source, int end) {
    int[] from = position(source, item.start()), to = position(source, end);
    return page(
        "durable:" + item.id() + ":" + attempt,
        new Target(
            item.path(),
            item.version(),
            position(source, source.length())[0],
            List.of(new Span(item.start(), end, from[0], to[0], from[1], to[1]))),
        source,
        item.start(),
        end);
  }

  @Tool(
      description =
          "Page frozen review targets, exclusions and source errors. Pass nextCursor unchanged as afterPath; null starts at the beginning. limit is 1..1000, default 100. Reading a page never completes work.")
  public dev.mikoto2000.rei.durable.DurableTaskStore.Page<
          dev.mikoto2000.rei.durable.DurableTaskStore.FileEntry>
      listReviewTargets(
          String taskId,
          @org.springframework.ai.tool.annotation.ToolParam(required = false) String afterPath,
          @org.springframework.ai.tool.annotation.ToolParam(required = false) Integer limit) {
    return tasks.files(
        taskScope(AgentRunScope.current()), taskId, afterPath, limit == null ? 100 : limit);
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
          "Revalidate source versions and rebuild a STALE_VERSION review job from its original files. Preserves historical findings, marks old-version ranges stale and starts the changed files again; never reuses old-version reviews. If no restart is needed, follow the returned guidance to continue the existing job.")
  public Job restartReview(String reviewJobId) throws java.io.IOException {
    var scope = scope(AgentRunScope.current());
    var old = reviewStatus(reviewJobId);
    if (!"STALE_VERSION".equals(old.status())) {
      String next =
          switch (old.status()) {
            case "COMPLETE" ->
                "The review is already complete. Use reviewFindings to inspect preserved findings and report the result.";
            case "REVIEW_CANCELLED", "FAILED" ->
                "This job is terminal. Use beginReview with the authorized targets and criteria if a new review is needed.";
            default ->
                "Use reviewPending to select unfinished ranges, then readReviewFile and submitReviewPage. "
                    + "If a delivered page is awaiting assessment, submit it first; re-read that same page if its body left the prompt.";
          };
      throw new ReviewResultRejectedException(
          "No restart was performed: current status="
              + old.status()
              + ". Source versions have been revalidated and the job is not STALE_VERSION. "
              + "Preserve saved assessments and do not repeat restartReview without a source change. "
              + next);
    }
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
          "Read an exact decoded review page. Pass reviewJobId, path, expectedVersion, UTF-16 offset, and tokenBudget 256..2048. Start at offset 0 and follow nextOffset while truncated. A version change invalidates the job. Prefer submitReviewPage with this page's offset/nextOffset; the server preserves exact hashes and coordinates. Batch related assessments first and reads at their known nextOffset afterwards in one tool-call response.")
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
    var page = sizedPage(reviewJobId, target, source, offset, tokenBudget, null);
    ledger.fetched(reviewJobId, scope, page.range());
    return page;
  }

  @ResultDelivery(value = ResultDelivery.Kind.FILE_CONTENT, reviewPages = true)
  @Tool(
      description =
          "Read up to 4 adjacent review pages from one immutable decoded source. maxPages 1..4; totalTokenBudget 512..2048 bounds the ENTIRE serialized result. Start at offset 0, then follow the last nextOffset while truncated. Reading is not delivery or assessment. Prefer submitReviewPages for the delivered batch.")
  public List<Page> readReviewPages(
      String reviewJobId,
      String path,
      String expectedVersion,
      int offset,
      int maxPages,
      int totalTokenBudget)
      throws java.io.IOException {
    if (maxPages < 1 || maxPages > 4 || totalTokenBudget < 512 || totalTokenBudget > 2048)
      throw new IllegalArgumentException("CONTEXT_BUDGET_INSUFFICIENT");
    var scope = scope(AgentRunScope.current());
    var job = ledger.get(reviewJobId, scope);
    var target =
        job.targets().stream()
            .filter(t -> t.path().equals(path))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("REVIEW_RESULT_INVALID"));
    if (!target.version().equals(expectedVersion)) throw new IllegalStateException("STALE_VERSION");
    var source = readSource(path);
    if (!target.version().equals(source.version())) {
      ledger.invalidate(reviewJobId, scope, path, source.version());
      throw new IllegalStateException("STALE_VERSION");
    }
    if (!boundary(source.text(), offset)) throw new IllegalArgumentException("INVALID_CURSOR");
    int count = Math.min(maxPages, Math.max(1, (totalTokenBudget - 64) / 256));
    var pages = new ArrayList<Page>();
    for (int i = 0; i < count; i++) {
      int allowance =
          i == count - 1
              ? totalTokenBudget
              : Math.min(
                  totalTokenBudget,
                  estimate(pages) + (totalTokenBudget - estimate(pages)) / (count - i));
      Page page;
      try {
        page = sizedPage(reviewJobId, target, source.text(), offset, allowance, pages);
      } catch (IllegalStateException insufficient) {
        if (pages.isEmpty()) throw insufficient;
        break;
      }
      pages.add(page);
      offset = page.nextOffset();
      if (!page.truncated()) break;
    }
    if (estimate(pages) > totalTokenBudget)
      throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
    for (var page : pages) ledger.fetched(reviewJobId, scope, page.range());
    return List.copyOf(pages);
  }

  private Page sizedPage(
      String reviewJobId,
      Target target,
      String source,
      int offset,
      int tokenBudget,
      List<Page> prefix) {
    int maxEnd = Math.min(source.length(), offset + tokenBudget * 4);
    if (maxEnd > offset
        && maxEnd < source.length()
        && Character.isLowSurrogate(source.charAt(maxEnd))) maxEnd--;
    int low = 0, high = source.codePointCount(offset, maxEnd);
    while (low < high) {
      int middle = low + (high - low + 1) / 2;
      var candidate =
          page(reviewJobId, target, source, offset, source.offsetByCodePoints(offset, middle));
      if (estimatePages(prefix, candidate) <= tokenBudget) low = middle;
      else high = middle - 1;
    }
    var page = page(reviewJobId, target, source, offset, source.offsetByCodePoints(offset, low));
    if (estimatePages(prefix, page) > tokenBudget || low == 0 && offset < source.length())
      throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
    return page;
  }

  private int estimatePages(List<Page> prefix, Page page) {
    if (prefix == null) return estimate(page);
    var pages = new ArrayList<>(prefix);
    pages.add(page);
    return estimate(pages);
  }

  /** 依頼を実行経路へ渡すため、レビュー結果を受け付ける。 */
  @Tool(
      description =
          "Submit a structured review for exactly one delivered range returned by readReviewFile. Provide assessment even if there are no findings. Findings must cite exact source evidence inside the range. Undelivered, changed-version, malformed or invented ranges are rejected.")
  public Job submitReviewResult(
      String reviewJobId, Range range, String assessment, List<Finding> findings)
      throws java.io.IOException {
    try {
      return submitExactReviewResult(reviewJobId, range, assessment, findings);
    } catch (IllegalArgumentException invalid) {
      if (!"REVIEW_RESULT_INVALID".equals(invalid.getMessage())) throw invalid;
      throw new ReviewResultRejectedException(
          "No assessment was accepted. Prefer submitReviewPage using the delivered page's path, offset and nextOffset. "
              + "Do not reconstruct hashes or source coordinates. Re-read the same page if its body left the prompt.");
    }
  }

  private Job submitExactReviewResult(
      String reviewJobId, Range range, String assessment, List<Finding> findings)
      throws java.io.IOException {
    var owner = AgentRunScope.current();
    var scope = scope(owner);
    var job = reviewStatus(reviewJobId);
    if ("STALE_VERSION".equals(job.status())) throw new IllegalStateException("STALE_VERSION");
    var source = readSource(range.path());
    return submitVerifiedReviewResult(reviewJobId, scope, range, assessment, findings, source);
  }

  private Job submitVerifiedReviewResult(
      String reviewJobId,
      Scope scope,
      Range range,
      String assessment,
      List<Finding> findings,
      Source source) {
    validateEvidence(range, findings, source);
    return ledger.reviewed(reviewJobId, scope, range, assessment, findings);
  }

  private void validateEvidence(Range range, List<Finding> findings, Source source) {
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
  }

  public record PageFinding(String id, String severity, String evidence, String assessment) {}

  public record FindingDisposition(
      String id, String status, List<Finding> findings, long revision, boolean changed) {
    public FindingDisposition {
      findings = List.copyOf(findings);
    }
  }

  @Tool(
      description =
          "Dismiss one owned review finding only after corroborating metadata, referenced source or rendered output disproves the alleged defect. "
              + "Provide its findingId, path and a nonempty reason citing that counterevidence. Optional evidence quote disambiguates repeated IDs. "
              + "Preserves original quote/assessment and records DISMISSED plus the reason. Does not modify source files or mark unread ranges reviewed. Repeated dismissal is idempotent.")
  public FindingDisposition dismissReviewFinding(
      String reviewJobId,
      String findingId,
      String path,
      String reason,
      @org.springframework.ai.tool.annotation.ToolParam(required = false) String evidence) {
    try {
      var transition =
          ledger.dismissFindingTransition(
              reviewJobId, scope(AgentRunScope.current()), findingId, path, evidence, reason);
      var job = transition.job();
      return new FindingDisposition(
          job.id(),
          job.status(),
          job.findings().stream()
              .filter(
                  f ->
                      f.id().equals(findingId)
                          && f.path().equals(path)
                          && (evidence == null || f.evidence().equals(evidence)))
              .toList(),
          job.revision(),
          transition.changed());
    } catch (IllegalArgumentException invalid) {
      if (!"REVIEW_RESULT_INVALID".equals(invalid.getMessage())) throw invalid;
      throw new ReviewResultRejectedException(
          "No finding was dismissed. Use an existing owned findingId/path, its exact evidence quote if needed, and a corroborated reason of 1..4096 characters.");
    }
  }

  public record VerifiedRange(Range range, boolean delivered, boolean reviewed, boolean stale) {}

  /** A receipt for this submission; the full assessments remain in the durable ledger. */
  public record PageSubmission(
      String id,
      String status,
      boolean accepted,
      List<VerifiedRange> ranges,
      List<Finding> findings,
      int reviewedRanges,
      int targetFiles,
      long revision) {
    public PageSubmission {
      ranges = List.copyOf(ranges);
      findings = List.copyOf(findings);
    }
  }

  @Tool(
      description =
          "Assess one delivered readReviewFile page using its path, offset and nextOffset unchanged. "
              + "The server selects its exact saved source range, hashes and coordinates. Provide an assessment even with no findings. "
              + "Each finding needs id, severity, an exact unique evidence quote within this page, and assessment; the server locates it. "
              + "Undelivered, stale, ambiguous or invented pages/quotes are rejected. No source files are changed.")
  public PageSubmission submitReviewPage(
      String reviewJobId,
      String path,
      int offset,
      int nextOffset,
      String assessment,
      List<PageFinding> findings)
      throws java.io.IOException {
    try {
      if (path == null) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      var validated = validateSources(reviewJobId, java.util.Set.of(path));
      var job = validated.job();
      if (job.status().equals("STALE_VERSION"))
        throw new ReviewResultRejectedException(
            "No assessment was accepted: source version changed. Use restartReview, then read the new page.");
      var prepared =
          prepareAssessment(
              job, path, offset, nextOffset, assessment, findings, validated.snapshots().get(path));
      var range = prepared.range();
      var located = prepared.findings();
      verifyUnchanged(reviewJobId, validated, path);
      var saved =
          submitVerifiedReviewResult(
              reviewJobId,
              scope(AgentRunScope.current()),
              range,
              assessment,
              located,
              validated.snapshots().get(path));
      return submission(saved, range, located);
    } catch (IllegalArgumentException invalid) {
      if (!"REVIEW_RESULT_INVALID".equals(invalid.getMessage())) throw invalid;
      throw new ReviewResultRejectedException(
          "No assessment was accepted. Use an owned delivered page, nonempty assessment, and findings=[] or exact unique evidence quotes.");
    }
  }

  public record PageAssessment(
      String path, int offset, int nextOffset, String assessment, List<PageFinding> findings) {}

  @Tool(
      description =
          "Atomically assess 1..4 delivered review pages. Copy each exact path/offset/nextOffset and supply a nonempty assessment and findings (or []). All pages must be owned, delivered, unchanged and have exact unique evidence. One invalid page rejects the whole batch; retries are idempotent. No source files change.")
  public List<PageSubmission> submitReviewPages(String reviewJobId, List<PageAssessment> pages)
      throws java.io.IOException {
    if (pages == null
        || pages.isEmpty()
        || pages.size() > 4
        || pages.stream().anyMatch(p -> p == null || p.path() == null))
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    var paths =
        pages.stream().map(PageAssessment::path).collect(java.util.stream.Collectors.toSet());
    var validated = validateSources(reviewJobId, paths);
    if (validated.job().status().equals("STALE_VERSION"))
      throw new IllegalStateException("STALE_VERSION");
    var prepared = new ArrayList<ReviewCoverageLedger.Assessment>();
    for (var page : pages)
      prepared.add(
          prepareAssessment(
              validated.job(),
              page.path(),
              page.offset(),
              page.nextOffset(),
              page.assessment(),
              page.findings(),
              validated.snapshots().get(page.path())));
    verifyUnchanged(reviewJobId, validated, pages.getLast().path());
    var saved = ledger.reviewedBatch(reviewJobId, scope(AgentRunScope.current()), prepared);
    return prepared.stream().map(p -> submission(saved, p.range(), p.findings())).toList();
  }

  private ReviewCoverageLedger.Assessment prepareAssessment(
      Job job,
      String path,
      int offset,
      int nextOffset,
      String assessment,
      List<PageFinding> findings,
      Source snapshot) {
    var matching =
        job.ranges().stream()
            .filter(
                e ->
                    e.delivered()
                        && !e.stale()
                        && e.range().path().equals(path)
                        && e.range().span().start() == offset
                        && e.range().span().end() == nextOffset)
            .toList();
    if (matching.size() != 1)
      throw new ReviewResultRejectedException(
          "No assessment was accepted. Use the exact path, offset and nextOffset of one delivered readReviewFile page. Re-read that page if necessary.");
    var range = matching.getFirst().range();
    var source = snapshot.text();
    if (findings == null) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    var located = new ArrayList<Finding>();
    for (var finding : findings) {
      String quote = finding.evidence();
      if (quote == null || quote.isBlank())
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      int start = source.indexOf(quote, offset);
      int other = start < 0 ? -1 : source.indexOf(quote, start + 1);
      if (start < offset
          || start + quote.length() > nextOffset
          || other >= 0 && other + quote.length() <= nextOffset)
        throw new ReviewResultRejectedException(
            "No assessment was accepted. Each evidence quote must match exactly once inside this delivered page; supply a longer unique quote if needed.");
      int end = start + quote.length();
      var from = position(source, start);
      var to = position(source, end);
      located.add(
          new Finding(
              finding.id(),
              finding.severity(),
              path,
              range.version(),
              new Span(start, end, from[0], to[0], from[1], to[1]),
              quote,
              finding.assessment(),
              "VERIFIED"));
    }
    validateEvidence(range, located, snapshot);
    return new ReviewCoverageLedger.Assessment(range, assessment, List.copyOf(located));
  }

  private PageSubmission submission(Job saved, Range range, List<Finding> located) {
    var entry =
        saved.ranges().stream().filter(e -> e.range().equals(range)).findFirst().orElseThrow();
    var acceptedFindings =
        saved.findings().stream()
            .filter(
                f ->
                    located.stream()
                        .anyMatch(
                            l ->
                                l.path().equals(f.path())
                                    && l.version().equals(f.version())
                                    && l.location().equals(f.location())
                                    && l.evidence().equals(f.evidence())))
            .toList();
    return new PageSubmission(
        saved.id(),
        saved.status(),
        true,
        List.of(
            new VerifiedRange(entry.range(), entry.delivered(), entry.reviewed(), entry.stale())),
        acceptedFindings,
        (int)
            saved.ranges().stream()
                .filter(e -> e.reviewed() && e.delivered() && !e.stale())
                .count(),
        saved.targets().size(),
        saved.revision());
  }

  /** 変更内容のレビューで用いるレビュー状態を返す。 */
  @Tool(
      description =
          "Inspect persisted review coverage and findings. Revalidates each current source version. COMPLETE only means verified range coverage; semantic review quality requires human judgment. Unreviewed/stale/failed ranges remain visible.")
  public Job reviewStatus(String reviewJobId) throws java.io.IOException {
    return validateSources(reviewJobId, java.util.Set.of()).job();
  }

  private record Source(String version, String text) {}

  private Source readSource(String path) throws java.io.IOException {
    var snapshot = files.get().reviewSnapshot(path);
    return new Source(snapshot.version(), snapshot.text());
  }

  private record Validated(Job job, Map<String, Source> snapshots) {
    private Validated {
      snapshots = Map.copyOf(snapshots);
    }
  }

  /** Decode only requested evidence, retaining fresh byte-hash checks for every target. */
  private Validated validateSources(String reviewJobId, java.util.Set<String> decoded)
      throws java.io.IOException {
    var scope = scope(AgentRunScope.current());
    var job = ledger.get(reviewJobId, scope);
    var snapshots = new LinkedHashMap<String, Source>();
    for (var target : job.targets()) {
      String version;
      try {
        if (decoded.contains(target.path())) {
          var snapshot = readSource(target.path());
          snapshots.put(target.path(), snapshot);
          version = snapshot.version();
        } else version = files.get().reviewVersion(target.path());
      } catch (java.io.IOException missing) {
        job = ledger.invalidate(reviewJobId, scope, target.path(), "unavailable");
        continue;
      }
      if (!target.version().equals(version))
        job = ledger.invalidate(reviewJobId, scope, target.path(), version);
    }
    return new Validated(job, snapshots);
  }

  /** Recheck current ownership and all source hashes immediately before accepting evidence. */
  private void verifyUnchanged(String id, Validated prior, String lastPath)
      throws java.io.IOException {
    var scope = scope(AgentRunScope.current());
    var job = ledger.get(id, scope);
    boolean changed = "STALE_VERSION".equals(job.status());
    for (var target :
        job.targets().stream()
            .sorted(Comparator.comparing(t -> t.path().equals(lastPath)))
            .toList()) {
      String version;
      try {
        version = files.get().reviewVersion(target.path());
      } catch (java.io.IOException missing) {
        ledger.invalidate(id, scope, target.path(), "unavailable");
        changed = true;
        continue;
      }
      if (!target.version().equals(version)) {
        ledger.invalidate(id, scope, target.path(), version);
        changed = true;
      }
      var snapshot = prior.snapshots().get(target.path());
      if (snapshot != null && !snapshot.version().equals(version)) changed = true;
    }
    if (changed) throw new IllegalStateException("STALE_VERSION");
  }

  /** Coverage-free, paginated access to preserved candidate findings. */
  public record FindingsPage(
      String id,
      String status,
      int offset,
      Integer nextOffset,
      int totalFindings,
      List<Finding> findings) {
    public FindingsPage {
      findings = List.copyOf(findings);
    }
  }

  /** Return candidates without replaying every target and saved assessment. */
  @Tool(
      description =
          "Get preserved review findings directly, without rereading the full coverage ledger. "
              + "Use offset=0 and limit=5 initially, then nextOffset until null. Includes original evidence, "
              + "assessment and verificationStatus (VERIFIED means source quote matched, not semantic correctness). "
              + "Revalidates current source versions; corroborate candidates before repairs or dismissReviewFinding.")
  public FindingsPage reviewFindings(String reviewJobId, int offset, int limit)
      throws java.io.IOException {
    var job = reviewStatus(reviewJobId);
    int start = Math.min(Math.max(0, offset), job.findings().size());
    int end = Math.min(start + Math.max(1, Math.min(10, limit)), job.findings().size());
    return new FindingsPage(
        job.id(),
        job.status(),
        start,
        end < job.findings().size() ? end : null,
        job.findings().size(),
        List.copyOf(job.findings().subList(start, end)));
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
      if (page.reviewJobId().startsWith("durable:")) {
        confirmDurable(owner, page);
      } else
        grouped.computeIfAbsent(page.reviewJobId(), ignored -> new ArrayList<>()).add(page.range());
    }
    grouped.forEach((job, ranges) -> ledger.delivered(job, scope(owner), ranges));
  }

  private void confirmDurable(AgentRunContext owner, Page page) {
    String[] receipt = page.reviewJobId().split(":", -1);
    if (receipt.length != 3) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    var work = workItems();
    dev.mikoto2000.rei.durable.WorkItemStore.Claim claim;
    try {
      claim = work.current(taskScope(owner), receipt[1], owner.runId(), receipt[2]);
    } catch (IllegalArgumentException stale) {
      if ("STALE_CLAIM".equals(stale.getMessage())
          || "TASK_RUN_NOT_BOUND".equals(stale.getMessage())) return;
      throw stale;
    }
    var item = claim.item();
    if (!page.range().path().equals(item.path())
        || !page.range().version().equals(item.version())
        || page.range().span().start() != item.start()
        || page.range().span().end() != item.end())
      throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    work.delivered(taskScope(owner), claim, page.range().contentHash());
  }

  /** 受信した内容を利用できるよう、ページを復元する。 */
  private static List<Page> decodePages(String result) throws java.io.IOException {
    var tree = StorageDatabase.JSON.readTree(result);
    if (tree.has("output")) {
      if (!tree.path("output").isTextual()
          || tree.path("truncated").asBoolean(true)
          || !hash(tree.path("output").asText()).equals(tree.path("outputSha256").asText()))
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      tree = StorageDatabase.JSON.readTree(tree.path("output").asText());
    }
    var nodes = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    if (tree.isArray()) {
      if (tree.isEmpty() || tree.size() > 4)
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      tree.forEach(nodes::add);
    } else nodes.add(tree);
    var pages = new ArrayList<Page>();
    for (var node : nodes) {
      var page = StorageDatabase.JSON.treeToValue(node, Page.class);
      if (page == null
          || page.reviewJobId() == null
          || page.range() == null
          || page.content() == null
          || !hash(page.content()).equals(page.range().contentHash()))
        throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      pages.add(page);
    }
    return List.copyOf(pages);
  }

  /** 変更内容のレビューで用いる受付記録を返す。 */
  public static Receipt receipt(String result) {
    try {
      var page = decodePages(result).getFirst();
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
          "Return missing review spans with exact source version and next character offset. Stale, undelivered and unreviewed spans remain pending. Use reviewFindings for preserved findings and restartReview for changed sources.")
  public List<Pending> reviewPending(String reviewJobId) throws java.io.IOException {
    return pending(reviewStatus(reviewJobId));
  }

  private List<Pending> pending(Job job) {
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
  private Pending pending(Target target, int start, int end, String state) {
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
              pages.addAll(decodePages(response.responseData()));
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
  private int estimate(Object page) {
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
                    page instanceof List<?> ? "readReviewPages" : "readReviewFile",
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

  /** Saved range assessments survive prompt eviction; this snapshot does not mark new reviews. */
  public String continuationSummary(AgentRunContext owner) {
    var jobs = ledger.jobs(scope(owner)).stream().map(id -> ledger.get(id, scope(owner))).toList();
    var incomplete = jobs.stream().filter(job -> !job.status().equals("COMPLETE")).toList();
    var rows = new ArrayList<Map<String, Object>>();
    for (var job : incomplete) {
      var row =
          Map.<String, Object>of(
              "reviewJobId",
              job.id(),
              "status",
              job.status(),
              "targetFiles",
              job.targets().size(),
              "reviewedRanges",
              job.ranges().stream()
                  .filter(e -> e.reviewed() && e.delivered() && !e.stale())
                  .count(),
              "pendingSample",
              pending(job).stream().limit(3).toList());
      rows.add(row);
      if (StorageDatabase.JSON.valueToTree(rows).toString().length() > 3500) {
        rows.removeLast();
        break;
      }
      if (rows.size() >= 3) break;
    }
    return StorageDatabase.JSON
        .valueToTree(
            Map.of(
                "registeredJobs",
                jobs.size(),
                "completedJobs",
                jobs.size() - incomplete.size(),
                "incompleteJobs",
                incomplete.size(),
                "findingJobs",
                jobs.stream()
                    .filter(job -> !job.findings().isEmpty())
                    .limit(3)
                    .map(
                        job ->
                            Map.of(
                                "reviewJobId",
                                job.id(),
                                "status",
                                job.status(),
                                "findings",
                                job.findings().size(),
                                "nextAction",
                                "Use reviewFindings(offset=0,limit=5); corroborate and resolve candidates. "
                                    + "Coverage COMPLETE alone does not mean requested repairs are finished."))
                    .toList(),
                "savedPendingSample",
                rows))
        .toString();
  }

  /** 終了時の状態を確定するため、実行を完了させる。 */
  public void finishRun(
      AgentRunContext owner, boolean cancelled, boolean failed, AgentRunContext parent) {
    if (owner == null || owner.projectId() == null) return;
    var scope = scope(owner);
    for (var id : ledger.jobs(scope)) {
      var job = ledger.get(id, scope);
      if (!job.participants().contains(scope)) continue;
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
