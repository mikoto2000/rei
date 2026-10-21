package dev.mikoto2000.rei.reflection;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.goal.FileGoalVerifier;
import dev.mikoto2000.rei.goal.GoalRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 振り返り教訓のユースケースを実現する。
 *
 * <p>Human-reviewed, scoped recommendations. No automatic long-term memory promotion.
 */
@Service
@ConditionalOnProperty(name = "rei.reflection.lessons-enabled", havingValue = "true")
public class ReflectionLessonService {
  /** 作業の振り返りにおける種類の選択肢。 */
  public enum Kind {
    /** 失敗情報照合規則。 */
    FAILURE_PATTERN,
    /** SUCCESSFUL方針。 */
    SUCCESSFUL_STRATEGY,
    /** REPEATED補正。 */
    REPEATED_CORRECTION
  }

  /**
   * 作業の振り返りで受け渡す観測内容を表す。
   *
   * @param id 識別子
   * @param state 状態
   * @param sourceKind 取得元種類
   * @param sourceId 取得元識別子
   * @param origin 起点
   * @param expected 期待する内容
   * @param actual 実際の
   * @param sourceHash 取得元ハッシュ値
   * @param createdAt 作成日時
   * @param complete 完了
   * @param verified 確認済み
   */
  public record Observation(
      String id,
      String state,
      String sourceKind,
      String sourceId,
      String origin,
      String expected,
      String actual,
      String sourceHash,
      Instant createdAt,
      boolean complete,
      boolean verified) {}

  /**
   * 作業の振り返りで受け渡す教訓を表す。
   *
   * @param id 識別子
   * @param state 状態
   * @param kind 種類
   * @param statement 記述内容
   * @param revision 改訂番号
   * @param occurrenceCount occurrence件数
   * @param evidence 根拠
   * @param evidenceHash 根拠ハッシュ値
   * @param counterexamples 反例
   * @param corrections 補正一覧
   * @param review レビュー
   */
  public record Lesson(
      String id,
      String state,
      Kind kind,
      String statement,
      long revision,
      int occurrenceCount,
      List<String> evidence,
      String evidenceHash,
      List<String> counterexamples,
      List<String> corrections,
      String review) {
    /** 受け取った情報からLessonを作成する。 */
    public Lesson {
      evidence = List.copyOf(evidence);
      counterexamples = List.copyOf(counterexamples);
      corrections = List.copyOf(corrections);
    }
  }

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新をまとめて確定するための窓口。 */
  private final TransactionTemplate tx;

  /** 実行一覧。 */
  private final RunReflectionRepository runs;

  /** 振り返り一覧。 */
  private final GoalReflectionRepository reflections;

  /** 目標一覧。 */
  private final GoalRepository goals;

  /** 確認の窓口。 */
  private final FileGoalVerifier verifier;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 有効状態。 */
  private final boolean enabled;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  /** 指定された値や利用先を使って、ReflectionLessonServiceを初期化する。 */
  public ReflectionLessonService(
      @Qualifier("memoryConsolidationDataSource") DataSource source,
      RunReflectionRepository runs,
      GoalReflectionRepository reflections,
      GoalRepository goals,
      FileGoalVerifier verifier,
      Clock clock,
      @Value("${rei.reflection.lessons-enabled:false}") boolean enabled) {
    db = JdbcClient.create(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.runs = runs;
    this.reflections = reflections;
    this.goals = goals;
    this.verifier = verifier;
    this.clock = clock;
    this.enabled = enabled;
    db.sql(
            "CREATE TABLE IF NOT EXISTS reflection_lesson_observations(id TEXT PRIMARY KEY,owner TEXT NOT NULL,source TEXT NOT NULL,document TEXT NOT NULL,UNIQUE(owner,source))")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS reflection_lessons(id TEXT PRIMARY KEY,owner TEXT NOT NULL,revision INTEGER NOT NULL,document TEXT NOT NULL)")
        .update();
    db.sql(
            "CREATE TABLE IF NOT EXISTS reflection_lesson_forgotten(owner TEXT NOT NULL,statement_hash TEXT NOT NULL,PRIMARY KEY(owner,statement_hash))")
        .update();
  }

  /** 作業の振り返りで用いる所有者を返す。 */
  private String owner(AgentRunContext context) {
    if (Thread.currentThread().isInterrupted())
      throw new IllegalArgumentException("Reflection review cancelled");
    if (!enabled) throw new IllegalArgumentException("Reflection lessons are disabled");
    if (context.projectId() == null || context.mode() != AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Exclusive Project ownership required");
    try {
      return encode(
          List.of(
              context.projectId(),
              context.projectRoot().toRealPath().toString(),
              context.conversationId()));
    } catch (java.io.IOException error) {
      throw new IllegalArgumentException("Project root unavailable", error);
    }
  }

  /** 状況を判断する根拠を得るため、振り返り教訓サービスを観測する。 */
  public Observation observe(AgentRunContext context, String kind, String id) {
    String scope = owner(context);
    Observation current = source(context, kind, id);
    return tx.execute(
        status -> {
          var previous =
              db.sql(
                      "SELECT document FROM reflection_lesson_observations WHERE owner=? AND source=?")
                  .params(scope, kind + ":" + id)
                  .query(String.class)
                  .optional();
          if (previous.isPresent()) return decode(previous.get(), Observation.class);
          quota("reflection_lesson_observations", 4096);
          db.sql("INSERT INTO reflection_lesson_observations VALUES(?,?,?,?)")
              .params(current.id(), scope, kind + ":" + id, encode(current))
              .update();
          return current;
        });
  }

  /** 作業の振り返りで用いる取得元を返す。 */
  private Observation source(AgentRunContext context, String kind, String id) {
    if ("USER_CORRECTION".equals(kind)) return observation(owner(context), id);
    if ("RUN".equals(kind)) {
      var item = runs.get(context.projectId(), id);
      if (!item.sessionId().equals(context.conversationId()))
        throw new IllegalArgumentException("Reflection session mismatch");
      return new Observation(
          "observation-" + hash(owner(context) + ":" + kind + ":" + id),
          "OBSERVATION",
          kind,
          id,
          item.runId() == null ? item.sourceId() : item.runId(),
          item.expected(),
          item.status() + ":" + item.gap(),
          hash(encode(item)),
          item.createdAt(),
          !item.actionsTruncated(),
          false);
    }
    if ("GOAL".equals(kind)) {
      var item = reflections.get(context.projectId(), id);
      var goal = goals.get(context.projectId(), item.goalId());
      if (!item.sessionId().equals(context.conversationId())
          || !goal.sessionId().equals(context.conversationId())
          || !goal.projectRoot().equals(context.projectRoot().toString()))
        throw new IllegalArgumentException("Goal ownership mismatch");
      boolean verified =
          "VERIFIED".equals(item.actual())
              && "COMPLETED".equals(goal.status())
              && "COMPLETED".equals(item.goalStatus())
              && Set.of("file_digest_verified", "criteria_verified", "completion_gate_verified")
                  .contains(goal.reason())
              && goal.reason().equals(item.actualReason())
              && Objects.equals(
                  goal.currentRunId() == null ? "" : goal.currentRunId(), item.runId())
              && reflections.matchesCriteria(item, goal)
              && verifier.verify(goal).satisfied();
      return new Observation(
          "observation-" + hash(owner(context) + ":" + kind + ":" + id),
          "OBSERVATION",
          kind,
          id,
          item.goalId(),
          item.expectedFile(),
          item.actual() + ":" + item.gap(),
          hash(encode(item)),
          item.createdAt(),
          true,
          verified);
    }
    throw new IllegalArgumentException("Unknown reflection source kind");
  }

  /** 作業の振り返りで用いる観測内容を返す。 */
  private Observation observation(String scope, String id) {
    return db.sql("SELECT document FROM reflection_lesson_observations WHERE owner=? AND id=?")
        .params(scope, id)
        .query(String.class)
        .optional()
        .map(value -> decode(value, Observation.class))
        .orElseThrow(
            () -> new IllegalArgumentException("Observation not found in this owner scope"));
  }

  /** 状況を判断する根拠を得るため、補正を観測する。 */
  public Observation observeCorrection(
      AgentRunContext context, String correctionId, String observationId, String correction) {
    String scope = owner(context);
    if (correctionId == null || !correctionId.matches("[A-Za-z0-9_-]{1,128}"))
      throw new IllegalArgumentException("Stable correction ID required");
    var base = observation(scope, observationId);
    String text = note(correction);
    String documentHash = hash(encode(List.of(base.id(), base.sourceHash(), text)));
    return tx.execute(
        status -> {
          String source = "USER_CORRECTION:" + correctionId;
          var previous =
              db.sql(
                      "SELECT document FROM reflection_lesson_observations WHERE owner=? AND source=?")
                  .params(scope, source)
                  .query(String.class)
                  .optional();
          if (previous.isPresent()) {
            var saved = decode(previous.get(), Observation.class);
            if (!saved.sourceHash().equals(documentHash))
              throw new IllegalArgumentException("Correction source cannot be replaced");
            return saved;
          }
          quota("reflection_lesson_observations", 4096);
          String id = "correction-" + hash(scope + ":" + correctionId);
          var saved =
              new Observation(
                  id,
                  "OBSERVATION",
                  "USER_CORRECTION",
                  id,
                  base.origin(),
                  base.actual(),
                  text,
                  documentHash,
                  clock.instant(),
                  base.complete(),
                  false);
          db.sql("INSERT INTO reflection_lesson_observations VALUES(?,?,?,?)")
              .params(id, scope, source, encode(saved))
              .update();
          return saved;
        });
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Lesson> list(AgentRunContext context) {
    String scope = owner(context);
    return db
        .sql("SELECT document FROM reflection_lessons WHERE owner=? ORDER BY rowid DESC LIMIT 128")
        .param(scope)
        .query(String.class)
        .list()
        .stream()
        .map(value -> decode(value, Lesson.class))
        .toList();
  }

  /** 後続の判断で選べる候補を用意するため、振り返り教訓サービスを提案する。 */
  public Lesson propose(AgentRunContext context, Kind kind, String statement, List<String> ids) {
    String scope = owner(context);
    Objects.requireNonNull(kind);
    String text = note(statement);
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (ids == null || ids.isEmpty() || ids.size() > 16 || new HashSet<>(ids).size() != ids.size())
      throw new IllegalArgumentException("One to sixteen distinct observations required");
    var facts = ids.stream().map(id -> observation(scope, id)).toList();
    return tx.execute(
        status -> {
          if (db.sql(
                      "SELECT COUNT(*) FROM reflection_lesson_forgotten WHERE owner=? AND statement_hash=?")
                  .params(scope, statementHash(text))
                  .query(Integer.class)
                  .single()
              > 0) throw new IllegalArgumentException("Forgotten lesson cannot be regenerated");
          quota("reflection_lessons", 1024);
          var lesson =
              new Lesson(
                  "lesson-" + UUID.randomUUID(),
                  "CANDIDATE_LESSON",
                  kind,
                  text,
                  0,
                  (int) facts.stream().map(Observation::origin).distinct().count(),
                  ids,
                  hash(encode(facts)),
                  List.of(),
                  List.of(),
                  "");
          db.sql("INSERT INTO reflection_lessons VALUES(?,?,?,?)")
              .params(lesson.id(), scope, 0, encode(lesson))
              .update();
          return lesson;
        });
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Lesson get(AgentRunContext context, String id) {
    String scope = owner(context);
    return read(scope, id);
  }

  /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
  private Lesson read(String scope, String id) {
    return db.sql("SELECT document FROM reflection_lessons WHERE owner=? AND id=?")
        .params(scope, id)
        .query(String.class)
        .optional()
        .map(value -> decode(value, Lesson.class))
        .orElseThrow(() -> new IllegalArgumentException("Lesson not found in this owner scope"));
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public Lesson validate(
      AgentRunContext context, String id, long revision, String evidenceHash, String review) {
    String scope = owner(context);
    String reviewed = note(review);
    var lesson = read(scope, id);
    check(lesson, revision);
    if (!lesson.state().equals("CANDIDATE_LESSON")
        || !lesson.evidenceHash().equals(evidenceHash)
        || lesson.occurrenceCount() < 3
        || !lesson.counterexamples().isEmpty()
        || !lesson.corrections().isEmpty())
      throw new IllegalArgumentException("Lesson validation gates failed");
    for (String ref : lesson.evidence()) {
      var saved = observation(scope, ref);
      var current = source(context, saved.sourceKind(), saved.sourceId());
      if (!saved.sourceHash().equals(current.sourceHash())
          || !current.complete()
          || saved.createdAt().isAfter(clock.instant())
          || saved.createdAt().isBefore(clock.instant().minus(Duration.ofDays(30))))
        throw new IllegalArgumentException("Evidence is stale, incomplete or changed");
      if (lesson.kind() == Kind.SUCCESSFUL_STRATEGY && !current.verified()
          || lesson.kind() == Kind.FAILURE_PATTERN && !current.actual().startsWith("FAILED:")
          || lesson.kind() == Kind.REPEATED_CORRECTION
              && !current.sourceKind().equals("USER_CORRECTION"))
        throw new IllegalArgumentException("Evidence does not support this lesson kind");
    }
    return tx.execute(
        status -> {
          owner(context);
          if (db.sql(
                      "SELECT COUNT(*) FROM reflection_lesson_forgotten WHERE owner=? AND statement_hash=?")
                  .params(scope, statementHash(lesson.statement()))
                  .query(Integer.class)
                  .single()
              > 0) throw new IllegalArgumentException("Forgotten lesson cannot be validated");
          return update(
              scope,
              lesson,
              new Lesson(
                  id,
                  "VALIDATED_LESSON",
                  lesson.kind(),
                  lesson.statement(),
                  revision + 1,
                  lesson.occurrenceCount(),
                  lesson.evidence(),
                  lesson.evidenceHash(),
                  lesson.counterexamples(),
                  lesson.corrections(),
                  reviewed));
        });
  }

  /** 作業の振り返りで用いるcounterexampleを返す。 */
  public Lesson counterexample(
      AgentRunContext context, String id, long revision, String observationId, String description) {
    String scope = owner(context);
    observation(scope, observationId);
    var lesson = read(scope, id);
    check(lesson, revision);
    var examples = new ArrayList<>(lesson.counterexamples());
    if (examples.size() >= 16) throw new IllegalArgumentException("Counterexample quota exceeded");
    examples.add(observationId + ": " + note(description));
    return update(
        scope,
        lesson,
        new Lesson(
            id,
            "REJECTED",
            lesson.kind(),
            lesson.statement(),
            revision + 1,
            lesson.occurrenceCount(),
            lesson.evidence(),
            lesson.evidenceHash(),
            examples,
            lesson.corrections(),
            lesson.review()));
  }

  /** 作業の振り返りで用いるcorrectを返す。 */
  public Lesson correct(AgentRunContext context, String id, long revision, String correction) {
    String scope = owner(context);
    var lesson = read(scope, id);
    check(lesson, revision);
    var corrections = new ArrayList<>(lesson.corrections());
    if (corrections.size() >= 16) throw new IllegalArgumentException("Correction quota exceeded");
    corrections.add(note(correction));
    return update(
        scope,
        lesson,
        new Lesson(
            id,
            "REJECTED",
            lesson.kind(),
            lesson.statement(),
            revision + 1,
            lesson.occurrenceCount(),
            lesson.evidence(),
            lesson.evidenceHash(),
            lesson.counterexamples(),
            corrections,
            lesson.review()));
  }

  /** 作業の振り返りで用いる記憶の削除を返す。 */
  public Lesson forget(AgentRunContext context, String id, long revision) {
    String scope = owner(context);
    return tx.execute(
        status -> {
          var lesson = read(scope, id);
          check(lesson, revision);
          db.sql("INSERT OR IGNORE INTO reflection_lesson_forgotten VALUES(?,?)")
              .params(scope, statementHash(lesson.statement()))
              .update();
          return update(
              scope,
              lesson,
              new Lesson(
                  id,
                  "REJECTED",
                  lesson.kind(),
                  lesson.statement(),
                  revision + 1,
                  lesson.occurrenceCount(),
                  lesson.evidence(),
                  lesson.evidenceHash(),
                  lesson.counterexamples(),
                  lesson.corrections(),
                  "FORGOTTEN"));
        });
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  private Lesson update(String scope, Lesson previous, Lesson next) {
    if (db.sql(
                "UPDATE reflection_lessons SET revision=?,document=? WHERE owner=? AND id=? AND revision=?")
            .params(next.revision(), encode(next), scope, next.id(), previous.revision())
            .update()
        != 1) throw new IllegalArgumentException("Stale lesson revision");
    return next;
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  private void check(Lesson lesson, long revision) {
    if (lesson.revision() != revision) throw new IllegalArgumentException("Stale lesson revision");
  }

  /** 作業の振り返りに関する使用枠を処理する。 */
  private void quota(String table, int limit) {
    if (db.sql("SELECT COUNT(*) FROM " + table).query(Integer.class).single() >= limit
        || db.sql("SELECT COALESCE(SUM(length(document)),0) FROM " + table)
                .query(Long.class)
                .single()
            > 8 * 1024 * 1024)
      throw new IllegalArgumentException("Reflection ledger quota exceeded");
  }

  /** 作業の振り返りで用いる補足を返す。 */
  private String note(String value) {
    if (value == null || value.isBlank() || value.length() > 2048)
      throw new IllegalArgumentException("Nonblank note up to 2048 characters required");
    return RunReflectionRepository.safe(value, 2048).strip();
  }

  /** 作業の振り返りで用いる記述内容ハッシュ値を返す。 */
  private String statementHash(String value) {
    return hash(value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(Object value) {
    try {
      String encoded = json.writeValueAsString(value);
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (encoded.length() > 65536)
        throw new IllegalArgumentException("Reflection record too large");
      return encoded;
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private <T> T decode(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (java.io.IOException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
