package dev.mikoto2000.rei.review;

import dev.mikoto2000.rei.storage.StorageDatabase;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 変更内容のレビューに必要なレビュー対象範囲記録台帳を扱う。
 *
 * <p>Conversation-independent evidence. API transitions are called by trusted execution boundaries.
 */
public final class ReviewCoverageLedger {
  /**
   * 変更内容のレビューで受け渡す対象範囲を表す。
   *
   * @param project プロジェクト
   * @param conversation 会話
   * @param run 実行
   */
  public record Scope(String project, String conversation, String run) {
    /** 受け取った情報からScopeを作成する。 */
    public Scope {
      require(project);
      require(conversation);
      require(run);
    }
  }

  /**
   * 変更内容のレビューで受け渡す計測区間を表す。
   *
   * @param start 開始
   * @param end 終了
   * @param startLine 開始行
   * @param endLine 終了行
   * @param startOffset 開始開始位置
   * @param endOffset 終了開始位置
   */
  public record Span(
      int start, int end, int startLine, int endLine, int startOffset, int endOffset) {
    /** 受け取った情報からSpanを作成する。 */
    public Span {
      if (start < 0
          || end < start
          || startLine < 1
          || endLine < startLine
          || startOffset < 0
          || endOffset < 0
          || startLine == endLine && endOffset < startOffset) throw invalid();
    }
  }

  /**
   * 変更内容のレビューで受け渡す対象を表す。
   *
   * @param path パス
   * @param version 形式のバージョン
   * @param lineCount 行件数
   * @param required 必須
   */
  public record Target(String path, String version, int lineCount, List<Span> required) {
    /** 受け取った情報からTargetを作成する。 */
    public Target {
      require(path);
      require(version);
      if (lineCount < 1 || required == null || required.isEmpty()) throw invalid();
      required = List.copyOf(required);
    }
  }

  /**
   * 変更内容のレビューで受け渡す対象範囲を表す。
   *
   * @param path パス
   * @param version 形式のバージョン
   * @param span 計測区間
   * @param contentHash 内容を照合するハッシュ
   */
  public record Range(String path, String version, Span span, String contentHash) {
    /** 受け取った情報からRangeを作成する。 */
    public Range {
      require(path);
      require(version);
      require(contentHash);
      Objects.requireNonNull(span);
    }
  }

  /**
   * 変更内容のレビューで受け渡す指摘を表す。
   *
   * @param id 識別子
   * @param severity 重大度
   * @param path パス
   * @param version 形式のバージョン
   * @param location 場所
   * @param evidence 根拠
   * @param assessment 評価
   * @param verificationStatus 確認結果状態
   */
  public record Finding(
      String id,
      String severity,
      String path,
      String version,
      Span location,
      String evidence,
      String assessment,
      String verificationStatus) {
    /** 受け取った情報からFindingを作成する。 */
    public Finding {
      require(id);
      require(severity);
      require(path);
      require(version);
      require(evidence);
      require(assessment);
      Objects.requireNonNull(location);
    }
  }

  /**
   * 変更内容のレビューで受け渡す項目を表す。
   *
   * @param range 対象範囲
   * @param delivered 提供済みの内容
   * @param reviewed レビュー済みの内容
   * @param stale 古い状態
   * @param assessment 評価
   */
  public record Entry(
      Range range, boolean delivered, boolean reviewed, boolean stale, String assessment) {}

  /**
   * 変更内容のレビューで受け渡す実行する作業を表す。
   *
   * @param id 識別子
   * @param owner 所有者
   * @param participants 参加者一覧
   * @param criteria 判断基準一覧
   * @param criteriaVersion 判断基準一覧形式のバージョン
   * @param targets 対象一覧
   * @param ranges 対象範囲一覧
   * @param findings 指摘一覧
   * @param status 状態
   * @param revision 改訂番号
   * @param createdAt 作成日時
   * @param updatedAt 更新日時
   */
  public record Job(
      String id,
      Scope owner,
      List<Scope> participants,
      String criteria,
      String criteriaVersion,
      List<Target> targets,
      List<Entry> ranges,
      List<Finding> findings,
      String status,
      long revision,
      Instant createdAt,
      Instant updatedAt) {
    /** 受け取った情報からJobを作成する。 */
    public Job {
      participants = List.copyOf(participants);
      targets = List.copyOf(targets);
      ranges = List.copyOf(ranges);
      findings = List.copyOf(findings);
    }
  }

  /** 最大記録バイト数。 */
  private static final int MAX_RECORD_BYTES = 2 * 1024 * 1024;

  /** 最大合計バイト数。 */
  private static final long MAX_TOTAL_BYTES = 64L * 1024 * 1024;

  /** データベース。 */
  private final StorageDatabase database;

  /** 指定された値や利用先を使って、ReviewCoverageLedgerを初期化する。 */
  public ReviewCoverageLedger(StorageDatabase database) {
    this.database = database;
  }

  /** 指定された内容から新しい対象を作成する。 */
  public Job create(Scope scope, String criteria, String criteriaVersion, List<Target> targets) {
    require(criteria);
    require(criteriaVersion);
    if (targets == null
        || targets.isEmpty()
        || targets.size() > 64
        || targets.stream().map(Target::path).distinct().count() != targets.size()) throw invalid();
    var now = Instant.now();
    var job =
        new Job(
            UUID.randomUUID().toString(),
            scope,
            List.of(scope),
            criteria,
            criteriaVersion,
            targets,
            List.of(),
            List.of(),
            "PENDING",
            0,
            now,
            now);
    return database.transaction(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT COUNT(*) FROM review_jobs WHERE project=? AND conversation=?")) {
            query.setString(1, scope.project());
            query.setString(2, scope.conversation());
            try (var rows = query.executeQuery()) {
              rows.next();
              if (rows.getInt(1) >= 128)
                throw new IllegalStateException("Review job capacity exceeded");
            }
          }
          write(db, job, true);
          writeParticipants(db, job);
          return job;
        });
  }

  /**
   * 新たな対象を利用できるよう、外部を作成する。
   *
   * <p>External delegates without a verified manifest remain explicitly unsealed and unverified.
   */
  public Job createExternal(
      Scope scope, String criteria, String criteriaVersion, List<Target> targets) {
    if (!targets.isEmpty()) {
      var job = create(scope, criteria, criteriaVersion, targets);
      return externalReport(job.id(), scope, "External delivery is opaque");
    }
    require(criteria);
    require(criteriaVersion);
    var now = Instant.now();
    var job =
        new Job(
            UUID.randomUUID().toString(),
            scope,
            List.of(scope),
            criteria,
            criteriaVersion,
            List.of(),
            List.of(),
            List.of(),
            "REVIEW_UNVERIFIED",
            0,
            now,
            now);
    return database.transaction(
        db -> {
          try (var query =
              db.prepareStatement(
                  "SELECT COUNT(*) FROM review_jobs WHERE project=? AND conversation=?")) {
            query.setString(1, scope.project());
            query.setString(2, scope.conversation());
            try (var rows = query.executeQuery()) {
              rows.next();
              if (rows.getInt(1) >= 128)
                throw new IllegalStateException("Review job capacity exceeded");
            }
          }
          write(db, job, true);
          writeParticipants(db, job);
          return job;
        });
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Job get(String id, Scope scope) {
    try {
      return database.read(db -> read(db, id, scope));
    } catch (IllegalStateException error) {
      if (error.getCause() instanceof IllegalArgumentException invalid) throw invalid;
      throw error;
    }
  }

  /** 変更内容のレビューで用いる実行する作業一覧を返す。 */
  public List<String> jobs(Scope scope) {
    return database.read(
        db -> {
          var ids = new ArrayList<String>();
          try (var query =
              db.prepareStatement(
                  "SELECT j.id FROM review_participants p JOIN review_jobs j ON j.id=p.job WHERE p.project=? AND p.conversation=? AND p.run=? ORDER BY j.updated,j.id LIMIT 129")) {
            query.setString(1, scope.project());
            query.setString(2, scope.conversation());
            query.setString(3, scope.run());
            try (var rows = query.executeQuery()) {
              while (rows.next()) ids.add(rows.getString(1));
            }
          }
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (ids.size() > 128)
            throw new IllegalStateException("Review job display capacity exceeded");
          return List.copyOf(ids);
        });
  }

  /** 指定された内容を保存先へ反映するため、参加者一覧を書き込む。 */
  public static void writeParticipants(java.sql.Connection db, Job job)
      throws java.sql.SQLException {
    try (var remove = db.prepareStatement("DELETE FROM review_participants WHERE job=?")) {
      remove.setString(1, job.id());
      remove.executeUpdate();
    }
    try (var insert =
        db.prepareStatement(
            "INSERT INTO review_participants(job,project,conversation,run) VALUES(?,?,?,?)")) {
      for (var scope : job.participants()) {
        insert.setString(1, job.id());
        insert.setString(2, scope.project());
        insert.setString(3, scope.conversation());
        insert.setString(4, scope.run());
        insert.addBatch();
      }
      insert.executeBatch();
    }
  }

  /** 変更内容のレビューで用いる許可を返す。 */
  public Job grant(String id, Scope parent, Scope child) {
    return update(
        id,
        parent,
        job -> {
          if (!job.owner().equals(parent) || !parent.project().equals(child.project()))
            throw new IllegalArgumentException("REVIEW_ACCESS_DENIED");
          if (job.participants().contains(child)) return job;
          var participants = new ArrayList<>(job.participants());
          participants.add(child);
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (participants.size() > 64) throw invalid();
          return new Job(
              job.id(),
              job.owner(),
              participants,
              job.criteria(),
              job.criteriaVersion(),
              job.targets(),
              job.ranges(),
              job.findings(),
              job.status(),
              job.revision(),
              job.createdAt(),
              job.updatedAt());
        });
  }

  /**
   * 保存された状況を引き継いで、中断した作業を再開する。
   *
   * <p>Only the verified checkpoint boundary may transfer an existing job to its resumed Run.
   */
  public Job resume(String id, Scope previous, Scope resumed) {
    if (!previous.project().equals(resumed.project())
        || !previous.conversation().equals(resumed.conversation()))
      throw new IllegalArgumentException("REVIEW_ACCESS_DENIED");
    return update(
        id,
        previous,
        job -> {
          if (job.participants().contains(resumed)) return job;
          var participants = new ArrayList<>(job.participants());
          participants.add(resumed);
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (participants.size() > 64) throw invalid();
          String status =
              Set.of("FAILED", "REVIEW_CANCELLED").contains(job.status())
                  ? "REVIEW_PARTIAL"
                  : job.status();
          return new Job(
              job.id(),
              job.owner(),
              participants,
              job.criteria(),
              job.criteriaVersion(),
              job.targets(),
              job.ranges(),
              job.findings(),
              status,
              job.revision(),
              job.createdAt(),
              job.updatedAt());
        });
  }

  /** 変更内容のレビューで用いるrebuildを返す。 */
  public Job rebuild(String id, Scope scope, List<Target> targets) {
    return update(
        id,
        scope,
        job -> {
          if (!job.status().equals("STALE_VERSION")
              || targets.size() != job.targets().size()
              || !new HashSet<>(targets.stream().map(Target::path).toList())
                  .equals(new HashSet<>(job.targets().stream().map(Target::path).toList())))
            throw invalid();
          var versions = new HashMap<String, String>();
          targets.forEach(target -> versions.put(target.path(), target.version()));
          var entries =
              job.ranges().stream()
                  .map(
                      entry ->
                          versions.get(entry.range().path()).equals(entry.range().version())
                              ? entry
                              : new Entry(
                                  entry.range(),
                                  entry.delivered(),
                                  entry.reviewed(),
                                  true,
                                  entry.assessment()))
                  .toList();
          var findings =
              job.findings().stream()
                  .map(
                      finding ->
                          versions.get(finding.path()).equals(finding.version())
                              ? finding
                              : new Finding(
                                  finding.id(),
                                  finding.severity(),
                                  finding.path(),
                                  finding.version(),
                                  finding.location(),
                                  finding.evidence(),
                                  finding.assessment(),
                                  "STALE"))
                  .toList();
          return new Job(
              job.id(),
              job.owner(),
              job.participants(),
              job.criteria(),
              job.criteriaVersion(),
              targets,
              entries,
              findings,
              "REVIEW_PARTIAL",
              job.revision(),
              job.createdAt(),
              job.updatedAt());
        });
  }

  /** 変更内容のレビューで用いる取得を返す。 */
  public Job fetched(String id, Scope scope, Range range) {
    return update(
        id,
        scope,
        job -> {
          validateTarget(job, range);
          for (var entry : job.ranges())
            if (!entry.stale() && sameLocation(entry.range(), range)) {
              if (!entry.range().equals(range)) throw invalid();
              return job;
            }
          if (job.ranges().size() >= 10000)
            throw new IllegalStateException("Review range capacity exceeded");
          var entries = new ArrayList<>(job.ranges());
          entries.add(new Entry(range, false, false, false, null));
          return copy(job, entries, job.findings(), "REVIEW_PARTIAL");
        });
  }

  /**
   * 変更内容のレビューで用いる提供済みの内容を返す。
   *
   * <p>Batch only the exact pages retained in a request that received a successful response.
   */
  public Job delivered(String id, Scope scope, List<Range> ranges) {
    return update(
        id,
        scope,
        job -> {
          var entries = new ArrayList<>(job.ranges());
          for (var range : ranges) {
            validateTarget(job, range);
            int index = find(entries, range);
            if (index < 0) throw invalid();
            var old = entries.get(index);
            if (!old.delivered()) entries.set(index, new Entry(range, true, false, false, null));
          }
          return entries.equals(job.ranges())
              ? job
              : copy(job, entries, job.findings(), "REVIEW_PARTIAL");
        });
  }

  /** 変更内容のレビューで用いるレビュー済みの内容を返す。 */
  public Job reviewed(
      String id, Scope scope, Range range, String assessment, List<Finding> findings) {
    require(assessment);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (assessment.length() > 16384 || findings == null || findings.size() > 100) throw invalid();
    return update(
        id,
        scope,
        job -> {
          validateTarget(job, range);
          var entries = new ArrayList<>(job.ranges());
          int index = find(entries, range);
          if (index < 0 || !entries.get(index).delivered() || entries.get(index).stale())
            throw invalid();
          var saved = new ArrayList<>(job.findings());
          for (var finding : findings) {
            if (!finding.path().equals(range.path())
                || !finding.version().equals(range.version())
                || !contains(range.span(), finding.location())
                || !"VERIFIED".equals(finding.verificationStatus())) throw invalid();
            if (saved.stream()
                .noneMatch(
                    old ->
                        old.path().equals(finding.path())
                            && old.version().equals(finding.version())
                            && old.location().equals(finding.location())
                            && old.evidence().equals(finding.evidence())
                            && old.assessment().equals(finding.assessment()))) saved.add(finding);
          }
          if (saved.size() > 2000)
            throw new IllegalStateException("Review finding capacity exceeded");
          var old = entries.get(index);
          if (old.reviewed() && saved.equals(job.findings())) return job;
          entries.set(index, new Entry(range, true, true, false, assessment));
          String status = complete(job.targets(), entries) ? "COMPLETE" : "REVIEW_PARTIAL";
          return copy(job, entries, saved, status);
        });
  }

  /** 変更内容のレビューで用いるキャッシュの無効化を返す。 */
  public Job invalidate(String id, Scope scope, String path, String actualVersion) {
    return update(
        id,
        scope,
        job -> {
          if (job.targets().stream().noneMatch(t -> t.path().equals(path))) throw invalid();
          if (job.targets().stream()
              .anyMatch(t -> t.path().equals(path) && t.version().equals(actualVersion)))
            return job;
          var entries =
              job.ranges().stream()
                  .map(
                      entry ->
                          entry.range().path().equals(path)
                              ? new Entry(
                                  entry.range(),
                                  entry.delivered(),
                                  entry.reviewed(),
                                  true,
                                  entry.assessment())
                              : entry)
                  .toList();
          return copy(job, entries, job.findings(), "STALE_VERSION");
        });
  }

  /** 変更内容のレビューで用いる外部レポートを返す。 */
  public Job externalReport(String id, Scope scope, String report) {
    require(report);
    return update(
        id,
        scope,
        job ->
            Set.of("STALE_VERSION", "REVIEW_CANCELLED", "FAILED").contains(job.status())
                ? job
                : copy(job, job.ranges(), job.findings(), "REVIEW_UNVERIFIED"));
  }

  /** 実行中の処理を停止する。 */
  public Job stop(String id, Scope scope, boolean cancelled) {
    return update(
        id,
        scope,
        job -> copy(job, job.ranges(), job.findings(), cancelled ? "REVIEW_CANCELLED" : "FAILED"));
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  private Job update(String id, Scope scope, UnaryOperator<Job> operation) {
    return database.transaction(
        db -> {
          var old = read(db, id, scope);
          var changed = operation.apply(old);
          if (changed.equals(old)) return old;
          var next =
              new Job(
                  old.id(),
                  old.owner(),
                  changed.participants(),
                  old.criteria(),
                  old.criteriaVersion(),
                  changed.targets(),
                  changed.ranges(),
                  changed.findings(),
                  changed.status(),
                  old.revision() + 1,
                  old.createdAt(),
                  Instant.now());
          write(db, next, false);
          if (!old.participants().equals(next.participants())) writeParticipants(db, next);
          return next;
        });
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private Job read(java.sql.Connection db, String id, Scope scope) throws Exception {
    UUID.fromString(id);
    try (var query = db.prepareStatement("SELECT record FROM review_jobs WHERE id=?")) {
      query.setString(1, id);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) throw new IllegalArgumentException("REVIEW_JOB_NOT_FOUND");
        var job = StorageDatabase.JSON.readValue(rows.getString(1), Job.class);
        if (!job.participants().contains(scope))
          throw new IllegalArgumentException("REVIEW_ACCESS_DENIED");
        return job;
      }
    }
  }

  /** 指定された内容を書き込み先へ反映する。 */
  private void write(java.sql.Connection db, Job job, boolean insert) throws Exception {
    byte[] encoded = StorageDatabase.JSON.writeValueAsBytes(job);
    if (encoded.length > MAX_RECORD_BYTES)
      throw new IllegalStateException("Review job capacity exceeded");
    try (var query =
        db.prepareStatement(
            "SELECT COALESCE(SUM(length(CAST(record AS BLOB))),0) FROM review_jobs WHERE id<>?")) {
      query.setString(1, job.id());
      try (var rows = query.executeQuery()) {
        rows.next();
        if (rows.getLong(1) + encoded.length > MAX_TOTAL_BYTES)
          throw new IllegalStateException("Review storage capacity exceeded");
      }
    }
    try (var query =
        db.prepareStatement(
            insert
                ? "INSERT INTO review_jobs(project,conversation,updated,record,id) VALUES(?,?,?,?,?)"
                : "UPDATE review_jobs SET project=?,conversation=?,updated=?,record=? WHERE id=?")) {
      query.setString(1, job.owner().project());
      query.setString(2, job.owner().conversation());
      query.setString(3, job.updatedAt().toString());
      query.setString(4, new String(encoded, java.nio.charset.StandardCharsets.UTF_8));
      query.setString(5, job.id());
      query.executeUpdate();
    }
  }

  /** 変更内容のレビューで用いる複製を返す。 */
  private static Job copy(Job job, List<Entry> entries, List<Finding> findings, String status) {
    return new Job(
        job.id(),
        job.owner(),
        job.participants(),
        job.criteria(),
        job.criteriaVersion(),
        job.targets(),
        entries,
        findings,
        status,
        job.revision(),
        job.createdAt(),
        job.updatedAt());
  }

  /** 利用条件に合わない指定を受け付けないよう、対象を検証する。 */
  private static void validateTarget(Job job, Range range) {
    if ("STALE_VERSION".equals(job.status())) throw new IllegalStateException("STALE_VERSION");
    if (Set.of("REVIEW_CANCELLED", "FAILED").contains(job.status())) throw invalid();
    var target =
        job.targets().stream()
            .filter(t -> t.path().equals(range.path()))
            .findFirst()
            .orElseThrow(ReviewCoverageLedger::invalid);
    if (!target.version().equals(range.version())) throw new IllegalStateException("STALE_VERSION");
    if (target.required().stream().noneMatch(span -> contains(span, range.span()))
        || range.span().endLine() > target.lineCount()) throw invalid();
  }

  /** 変更内容のレビューで判断に用いる含有の条件を確認する。 */
  private static boolean contains(Span outer, Span inner) {
    return outer.start() <= inner.start() && inner.end() <= outer.end();
  }

  /** 変更内容のレビューにおいて、同一場所に該当するか判定する。 */
  private static boolean sameLocation(Range a, Range b) {
    return a.path().equals(b.path())
        && a.version().equals(b.version())
        && a.span().equals(b.span());
  }

  /** 対象を特定できるよう、レビュー対象範囲記録台帳を探す。 */
  private static int find(List<Entry> entries, Range range) {
    for (int i = 0; i < entries.size(); i++)
      if (!entries.get(i).stale() && entries.get(i).range().equals(range)) return i;
    return -1;
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  private static boolean complete(List<Target> targets, List<Entry> entries) {
    if (targets.isEmpty()) return false;
    for (var target : targets)
      for (var required : target.required()) {
        int cursor = required.start();
        var ranges =
            entries.stream()
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
        if (required.start() == required.end() && ranges.stream().noneMatch(required::equals))
          return false;
        for (var span : ranges) {
          if (span.start() > cursor) break;
          cursor = Math.max(cursor, span.end());
        }
        if (cursor < required.end()) return false;
      }
    return true;
  }

  /** 条件を満たさない操作を拒否するため、レビュー対象範囲記録台帳の前提条件を確認する。 */
  private static void require(String value) {
    if (value == null || value.isBlank() || value.length() > 16384) throw invalid();
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("REVIEW_RESULT_INVALID");
  }
}
