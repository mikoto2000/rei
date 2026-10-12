package dev.mikoto2000.rei.review;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import org.springframework.ai.tool.annotation.Tool;
import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.contextbudget.*;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.review.ReviewCoverageLedger.*;

/** Explicit review protocol. Reading never means delivery, and delivery never means reviewed. */
public final class ReviewTools {
  public record Page(String reviewJobId, Range range, int offset, int nextOffset, boolean truncated, String content) { }
  public record Receipt(String job, Range range, String serializedHash) { }
  private final ReviewCoverageLedger ledger;
  private final RawToolResultStore raw;
  private final java.util.function.Supplier<Tools> files;
  private final TokenEstimator estimator;
  public ReviewTools(ReviewCoverageLedger ledger, RawToolResultStore raw, Tools files, TokenEstimator estimator) {
    this(ledger, raw, () -> files, estimator);
  }
  public ReviewTools(ReviewCoverageLedger ledger, RawToolResultStore raw, java.util.function.Supplier<Tools> files, TokenEstimator estimator) {
    this.ledger = ledger; this.raw = raw; this.files = files; this.estimator = estimator;
  }
  public static Scope scope(AgentRunContext owner) {
    if (owner == null || owner.projectId() == null) throw new IllegalStateException("A Project run is required");
    return new Scope(owner.projectId(), owner.conversationId(), owner.runId());
  }
  @Tool(description = "Begin a coverage-verified review of explicit Project UTF-8 files. Criteria and criteriaVersion are required. Read pages with readReviewFile, then submit one structured result for each delivered range. Reading alone never completes a review.")
  public Job beginReview(List<String> paths, String criteria, String criteriaVersion) throws java.io.IOException {
    var owner = AgentRunScope.current(); var scope = scope(owner);
    if (paths == null || paths.isEmpty() || paths.size() > 64) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    var targets = new ArrayList<Target>(); int bytes = 0;
    for (var path : paths) {
      var snapshot = files.get().reviewSnapshot(path); bytes += snapshot.text().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > 8 * 1024 * 1024) throw new IllegalArgumentException("Review snapshot budget exceeded");
      var end = position(snapshot.text(), snapshot.text().length());
      targets.add(new Target(snapshot.path(), snapshot.version(), end[0],
          List.of(new Span(0, snapshot.text().length(), 1, end[0], 0, end[1]))));
    }
    return ledger.create(scope, criteria, criteriaVersion, targets);
  }
  @ResultDelivery(ResultDelivery.Kind.FILE_CONTENT)
  @Tool(description = "Read an exact UTF-8 review page. Pass reviewJobId, path, expectedVersion, UTF-16 offset, and tokenBudget 256..2048. Start at offset 0 and follow nextOffset while truncated. A version change invalidates the job. Preserve each returned range unchanged in submitReviewResult.")
  public Page readReviewFile(String reviewJobId, String path, String expectedVersion, int offset, int tokenBudget) throws java.io.IOException {
    var owner = AgentRunScope.current(); var scope = scope(owner); var job = ledger.get(reviewJobId, scope);
    var target = job.targets().stream().filter(t -> t.path().equals(path)).findFirst().orElseThrow(() -> new IllegalArgumentException("REVIEW_RESULT_INVALID"));
    if (!target.version().equals(expectedVersion)) throw new IllegalStateException("STALE_VERSION");
    if (tokenBudget < 256 || tokenBudget > 2048) throw new IllegalArgumentException("CONTEXT_BUDGET_INSUFFICIENT");
    var snapshot = files.get().reviewSnapshot(path);
    if (!target.version().equals(snapshot.version())) { ledger.invalidate(reviewJobId, scope, path, snapshot.version()); throw new IllegalStateException("STALE_VERSION"); }
    String source = snapshot.text();
    if (offset < 0 || offset > source.length() || offset > 0 && offset < source.length()
        && Character.isHighSurrogate(source.charAt(offset - 1)) && Character.isLowSurrogate(source.charAt(offset))) throw new IllegalArgumentException("INVALID_CURSOR");
    int maxEnd = Math.min(source.length(), offset + tokenBudget * 4);
    if (maxEnd > offset && maxEnd < source.length() && Character.isLowSurrogate(source.charAt(maxEnd))) maxEnd--;
    int low = 0, high = source.codePointCount(offset, maxEnd);
    while (low < high) {
      int middle = low + (high - low + 1) / 2;
      var candidate = page(reviewJobId, target, source, offset, source.offsetByCodePoints(offset, middle));
      if (estimate(candidate) <= tokenBudget) low = middle; else high = middle - 1;
    }
    var page = page(reviewJobId, target, source, offset, source.offsetByCodePoints(offset, low));
    if (estimate(page) > tokenBudget || low == 0 && offset < source.length()) throw new IllegalStateException("CONTEXT_BUDGET_INSUFFICIENT");
    ledger.fetched(reviewJobId, scope, page.range());
    return page;
  }
  @Tool(description = "Submit a structured review for exactly one delivered range returned by readReviewFile. Provide assessment even if there are no findings. Findings must cite exact source evidence inside the range. Undelivered, changed-version, malformed or invented ranges are rejected.")
  public Job submitReviewResult(String reviewJobId, Range range, String assessment, List<Finding> findings) throws java.io.IOException {
    var owner = AgentRunScope.current(); var scope = scope(owner);
    var job = reviewStatus(reviewJobId);
    if ("STALE_VERSION".equals(job.status())) throw new IllegalStateException("STALE_VERSION");
    var source = files.get().reviewSnapshot(range.path());
    if (!range.version().equals(source.version()) || range.span().end() > source.text().length()
        || !hash(source.text().substring(range.span().start(), range.span().end())).equals(range.contentHash())) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    if (findings == null) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    for (var finding : findings) {
      if (finding.location().start() < range.span().start() || finding.location().end() > range.span().end()
          || !source.text().substring(finding.location().start(), finding.location().end()).equals(finding.evidence())) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
    }
    return ledger.reviewed(reviewJobId, scope, range, assessment, findings);
  }
  @Tool(description = "Inspect persisted review coverage and findings. Revalidates each current source version. COMPLETE only means verified range coverage; semantic review quality requires human judgment. Unreviewed/stale/failed ranges remain visible.")
  public Job reviewStatus(String reviewJobId) throws java.io.IOException {
    var scope = scope(AgentRunScope.current()); var job = ledger.get(reviewJobId, scope);
    for (var target : job.targets()) {
      var snapshot = files.get().reviewSnapshot(target.path());
      if (!target.version().equals(snapshot.version())) job = ledger.invalidate(reviewJobId, scope, target.path(), snapshot.version());
    }
    return job;
  }
  /** Called only after a model request containing these exact pages has successfully completed. */
  public void confirmed(AgentRunContext owner, List<Page> pages) {
    var grouped = new LinkedHashMap<String, List<Range>>();
    for (var page : pages) {
      if (!hash(page.content()).equals(page.range().contentHash())) throw new IllegalArgumentException("REVIEW_RESULT_INVALID");
      grouped.computeIfAbsent(page.reviewJobId(), ignored -> new ArrayList<>()).add(page.range());
    }
    grouped.forEach((job, ranges) -> ledger.delivered(job, scope(owner), ranges));
  }
  public static Receipt receipt(String result) {
    try { var page = StorageDatabase.JSON.readValue(result, Page.class); return new Receipt(page.reviewJobId(), page.range(), hash(result)); }
    catch (java.io.IOException invalid) { throw new IllegalArgumentException("REVIEW_RESULT_INVALID", invalid); }
  }
  public void confirmedPrompt(org.springframework.ai.chat.prompt.Prompt prompt, AgentRunContext owner) {
    var pages = new ArrayList<Page>();
    for (var message : prompt.getInstructions()) if (message instanceof org.springframework.ai.chat.messages.ToolResponseMessage tool
        && tool.getMetadata().get("rei.reviewReceipts") instanceof Map<?, ?> receipts) {
      for (var response : tool.getResponses()) if (receipts.get(response.id()) instanceof Receipt receipt
          && hash(response.responseData()).equals(receipt.serializedHash())) {
        try { pages.add(StorageDatabase.JSON.readValue(response.responseData(), Page.class)); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("REVIEW_RESULT_INVALID", invalid); }
      }
    }
    if (!pages.isEmpty()) confirmed(owner, pages);
  }
  private Page page(String job, Target target, String source, int start, int end) {
    int[] from = position(source, start), to = position(source, end);
    String body = source.substring(start, end);
    return new Page(job, new Range(target.path(), target.version(), new Span(start, end, from[0], to[0], from[1], to[1]), hash(body)), start, end, end < source.length(), body);
  }
  private int estimate(Page page) { try { return estimator.text(StorageDatabase.JSON.writeValueAsString(page)); } catch (java.io.IOException error) { throw new IllegalStateException(error); } }
  private static int[] position(String source, int offset) {
    int line = 1, beginning = 0;
    for (int i = 0; i < offset; i++) if (source.charAt(i) == '\n') { line++; beginning = i + 1; }
    return new int[]{line, offset - beginning};
  }
  public static String hash(String content) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
    catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
  }
}
