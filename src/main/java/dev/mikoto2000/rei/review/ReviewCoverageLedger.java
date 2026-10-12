package dev.mikoto2000.rei.review;

import java.time.Instant;
import java.util.*;
import java.util.function.UnaryOperator;
import dev.mikoto2000.rei.storage.StorageDatabase;

/** Conversation-independent evidence. API transitions are called by trusted execution boundaries. */
public final class ReviewCoverageLedger {
  public record Scope(String project, String conversation, String run) {
    public Scope { require(project); require(conversation); require(run); }
  }
  public record Span(int start, int end, int startLine, int endLine, int startOffset, int endOffset) {
    public Span {
      if (start < 0 || end < start || startLine < 1 || endLine < startLine || startOffset < 0 || endOffset < 0
          || startLine == endLine && endOffset < startOffset) throw invalid();
    }
  }
  public record Target(String path, String version, int lineCount, List<Span> required) {
    public Target { require(path); require(version); if (lineCount < 1 || required == null || required.isEmpty()) throw invalid(); required = List.copyOf(required); }
  }
  public record Range(String path, String version, Span span, String contentHash) {
    public Range { require(path); require(version); require(contentHash); Objects.requireNonNull(span); }
  }
  public record Finding(String id, String severity, String path, String version, Span location,
      String evidence, String assessment, String verificationStatus) {
    public Finding { require(id); require(severity); require(path); require(version); require(evidence); require(assessment); Objects.requireNonNull(location); }
  }
  public record Entry(Range range, boolean delivered, boolean reviewed, boolean stale, String assessment) { }
  public record Job(String id, Scope owner, List<Scope> participants, String criteria, String criteriaVersion,
      List<Target> targets, List<Entry> ranges, List<Finding> findings, String status, long revision,
      Instant createdAt, Instant updatedAt) {
    public Job { participants = List.copyOf(participants); targets = List.copyOf(targets); ranges = List.copyOf(ranges); findings = List.copyOf(findings); }
  }
  private static final int MAX_RECORD_BYTES = 2 * 1024 * 1024;
  private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;
  private final StorageDatabase database;
  public ReviewCoverageLedger(StorageDatabase database) { this.database = database; }
  public Job create(Scope scope, String criteria, String criteriaVersion, List<Target> targets) {
    require(criteria); require(criteriaVersion);
    if (targets == null || targets.isEmpty() || targets.size() > 64 || targets.stream().map(Target::path).distinct().count() != targets.size()) throw invalid();
    var now = Instant.now();
    var job = new Job(UUID.randomUUID().toString(), scope, List.of(scope), criteria, criteriaVersion,
        targets, List.of(), List.of(), "PENDING", 0, now, now);
    return database.transaction(db -> {
      try (var query = db.prepareStatement("SELECT COUNT(*) FROM review_jobs WHERE project=? AND conversation=?")) {
        query.setString(1, scope.project()); query.setString(2, scope.conversation());
        try (var rows = query.executeQuery()) { rows.next(); if (rows.getInt(1) >= 128) throw new IllegalStateException("Review job capacity exceeded"); }
      }
      write(db, job, true); return job;
    });
  }
  public Job get(String id, Scope scope) {
    try { return database.read(db -> read(db, id, scope)); }
    catch (IllegalStateException error) {
      if (error.getCause() instanceof IllegalArgumentException invalid) throw invalid;
      throw error;
    }
  }
  public List<String> jobs(Scope scope) {
    return database.read(db -> {
      var ids = new ArrayList<String>();
      try (var query = db.prepareStatement("SELECT id,record FROM review_jobs WHERE project=? AND conversation=? ORDER BY updated,id LIMIT 129")) {
        query.setString(1, scope.project()); query.setString(2, scope.conversation());
        try (var rows = query.executeQuery()) { while (rows.next()) {
          var job = StorageDatabase.JSON.readValue(rows.getString(2), Job.class);
          if (job.participants().contains(scope)) ids.add(rows.getString(1));
        } }
      }
      return List.copyOf(ids);
    });
  }
  public Job grant(String id, Scope parent, Scope child) {
    return update(id, parent, job -> {
      if (!job.owner().equals(parent) || !parent.project().equals(child.project())) throw new IllegalArgumentException("REVIEW_ACCESS_DENIED");
      if (job.participants().contains(child)) return job;
      var participants = new ArrayList<>(job.participants()); participants.add(child);
      if (participants.size() > 64) throw invalid();
      return new Job(job.id(), job.owner(), participants, job.criteria(), job.criteriaVersion(), job.targets(), job.ranges(), job.findings(), job.status(), job.revision(), job.createdAt(), job.updatedAt());
    });
  }
  /** Only the verified checkpoint boundary may transfer an existing job to its resumed Run. */
  public Job resume(String id, Scope previous, Scope resumed) {
    if (!previous.project().equals(resumed.project()) || !previous.conversation().equals(resumed.conversation()))
      throw new IllegalArgumentException("REVIEW_ACCESS_DENIED");
    return update(id, previous, job -> {
      if (job.participants().contains(resumed)) return job;
      var participants = new ArrayList<>(job.participants()); participants.add(resumed);
      if (participants.size() > 64) throw invalid();
      String status = Set.of("FAILED","REVIEW_CANCELLED").contains(job.status()) ? "REVIEW_PARTIAL" : job.status();
      return new Job(job.id(),job.owner(),participants,job.criteria(),job.criteriaVersion(),job.targets(),job.ranges(),job.findings(),status,job.revision(),job.createdAt(),job.updatedAt());
    });
  }
  public Job fetched(String id, Scope scope, Range range) {
    return update(id, scope, job -> {
      validateTarget(job, range);
      for (var entry : job.ranges()) if (sameLocation(entry.range(), range)) {
        if (!entry.range().equals(range)) throw invalid(); return job;
      }
      if (job.ranges().size() >= 10000) throw new IllegalStateException("Review range capacity exceeded");
      var entries = new ArrayList<>(job.ranges()); entries.add(new Entry(range, false, false, false, null));
      return copy(job, entries, job.findings(), "REVIEW_PARTIAL");
    });
  }
  /** Batch only the exact pages retained in a request that received a successful response. */
  public Job delivered(String id, Scope scope, List<Range> ranges) {
    return update(id, scope, job -> {
      var entries = new ArrayList<>(job.ranges());
      for (var range : ranges) {
        validateTarget(job, range); int index = find(entries, range);
        if (index < 0) throw invalid(); var old = entries.get(index);
        if (!old.delivered()) entries.set(index, new Entry(range, true, false, false, null));
      }
      return entries.equals(job.ranges()) ? job : copy(job, entries, job.findings(), "REVIEW_PARTIAL");
    });
  }
  public Job reviewed(String id, Scope scope, Range range, String assessment, List<Finding> findings) {
    require(assessment); if (assessment.length() > 16384 || findings == null || findings.size() > 100) throw invalid();
    return update(id, scope, job -> {
      validateTarget(job, range); var entries = new ArrayList<>(job.ranges()); int index = find(entries, range);
      if (index < 0 || !entries.get(index).delivered() || entries.get(index).stale()) throw invalid();
      var saved = new ArrayList<>(job.findings());
      for (var finding : findings) {
        if (!finding.path().equals(range.path()) || !finding.version().equals(range.version())
            || !contains(range.span(), finding.location()) || !"VERIFIED".equals(finding.verificationStatus())) throw invalid();
        if (saved.stream().noneMatch(old -> old.path().equals(finding.path()) && old.version().equals(finding.version())
            && old.location().equals(finding.location()) && old.evidence().equals(finding.evidence()) && old.assessment().equals(finding.assessment()))) saved.add(finding);
      }
      if (saved.size() > 2000) throw new IllegalStateException("Review finding capacity exceeded");
      var old = entries.get(index);
      if (old.reviewed() && saved.equals(job.findings())) return job;
      entries.set(index, new Entry(range, true, true, false, assessment));
      String status = complete(job.targets(), entries) ? "COMPLETE" : "REVIEW_PARTIAL";
      return copy(job, entries, saved, status);
    });
  }
  public Job invalidate(String id, Scope scope, String path, String actualVersion) {
    return update(id, scope, job -> {
      if (job.targets().stream().noneMatch(t -> t.path().equals(path))) throw invalid();
      if (job.targets().stream().anyMatch(t -> t.path().equals(path) && t.version().equals(actualVersion))) return job;
      var entries = job.ranges().stream().map(entry -> entry.range().path().equals(path)
          ? new Entry(entry.range(), entry.delivered(), entry.reviewed(), true, entry.assessment()) : entry).toList();
      return copy(job, entries, job.findings(), "STALE_VERSION");
    });
  }
  public Job externalReport(String id, Scope scope, String report) { require(report); return update(id, scope, job -> Set.of("STALE_VERSION", "REVIEW_CANCELLED", "FAILED").contains(job.status()) ? job : copy(job, job.ranges(), job.findings(), "REVIEW_UNVERIFIED")); }
  public Job stop(String id, Scope scope, boolean cancelled) { return update(id, scope, job -> copy(job, job.ranges(), job.findings(), cancelled ? "REVIEW_CANCELLED" : "FAILED")); }
  private Job update(String id, Scope scope, UnaryOperator<Job> operation) {
    return database.transaction(db -> {
      var old = read(db, id, scope); var changed = operation.apply(old);
      if (changed.equals(old)) return old;
      var next = new Job(old.id(), old.owner(), changed.participants(), old.criteria(), old.criteriaVersion(),
          changed.targets(), changed.ranges(), changed.findings(), changed.status(), old.revision() + 1, old.createdAt(), Instant.now());
      write(db, next, false); return next;
    });
  }
  private Job read(java.sql.Connection db, String id, Scope scope) throws Exception {
    UUID.fromString(id);
    try (var query = db.prepareStatement("SELECT record FROM review_jobs WHERE id=?")) {
      query.setString(1, id); try (var rows = query.executeQuery()) {
        if (!rows.next()) throw new IllegalArgumentException("REVIEW_JOB_NOT_FOUND");
        var job = StorageDatabase.JSON.readValue(rows.getString(1), Job.class);
        if (!job.participants().contains(scope)) throw new IllegalArgumentException("REVIEW_ACCESS_DENIED"); return job;
      }
    }
  }
  private void write(java.sql.Connection db, Job job, boolean insert) throws Exception {
    byte[] encoded = StorageDatabase.JSON.writeValueAsBytes(job);
    if (encoded.length > MAX_RECORD_BYTES) throw new IllegalStateException("Review job capacity exceeded");
    try (var query = db.prepareStatement("SELECT COALESCE(SUM(length(CAST(record AS BLOB))),0) FROM review_jobs WHERE id<>?")) {
      query.setString(1, job.id()); try (var rows = query.executeQuery()) { rows.next(); if (rows.getLong(1) + encoded.length > MAX_TOTAL_BYTES) throw new IllegalStateException("Review storage capacity exceeded"); }
    }
    try (var query = db.prepareStatement(insert ? "INSERT INTO review_jobs(project,conversation,updated,record,id) VALUES(?,?,?,?,?)"
        : "UPDATE review_jobs SET project=?,conversation=?,updated=?,record=? WHERE id=?")) {
      query.setString(1, job.owner().project()); query.setString(2, job.owner().conversation()); query.setString(3, job.updatedAt().toString());
      query.setString(4, new String(encoded, java.nio.charset.StandardCharsets.UTF_8)); query.setString(5, job.id()); query.executeUpdate();
    }
  }
  private static Job copy(Job job, List<Entry> entries, List<Finding> findings, String status) {
    return new Job(job.id(), job.owner(), job.participants(), job.criteria(), job.criteriaVersion(), job.targets(), entries, findings, status, job.revision(), job.createdAt(), job.updatedAt());
  }
  private static void validateTarget(Job job, Range range) {
    if ("STALE_VERSION".equals(job.status())) throw new IllegalStateException("STALE_VERSION");
    if (Set.of("REVIEW_CANCELLED", "FAILED").contains(job.status())) throw invalid();
    var target = job.targets().stream().filter(t -> t.path().equals(range.path())).findFirst().orElseThrow(ReviewCoverageLedger::invalid);
    if (!target.version().equals(range.version())) throw new IllegalStateException("STALE_VERSION");
    if (target.required().stream().noneMatch(span -> contains(span, range.span())) || range.span().endLine() > target.lineCount()) throw invalid();
  }
  private static boolean contains(Span outer, Span inner) { return outer.start() <= inner.start() && inner.end() <= outer.end(); }
  private static boolean sameLocation(Range a, Range b) { return a.path().equals(b.path()) && a.version().equals(b.version()) && a.span().equals(b.span()); }
  private static int find(List<Entry> entries, Range range) { for (int i = 0; i < entries.size(); i++) if (entries.get(i).range().equals(range)) return i; return -1; }
  private static boolean complete(List<Target> targets, List<Entry> entries) {
    for (var target : targets) for (var required : target.required()) {
      int cursor = required.start();
      var ranges = entries.stream().filter(e -> e.reviewed() && e.delivered() && !e.stale() && e.range().path().equals(target.path())
          && e.range().version().equals(target.version())).map(e -> e.range().span()).sorted(Comparator.comparingInt(Span::start)).toList();
      if (required.start() == required.end() && ranges.stream().noneMatch(required::equals)) return false;
      for (var span : ranges) { if (span.start() > cursor) break; cursor = Math.max(cursor, span.end()); }
      if (cursor < required.end()) return false;
    }
    return true;
  }
  private static void require(String value) { if (value == null || value.isBlank() || value.length() > 16384) throw invalid(); }
  private static IllegalArgumentException invalid() { return new IllegalArgumentException("REVIEW_RESULT_INVALID"); }
}
