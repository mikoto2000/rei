package dev.mikoto2000.rei.reflection;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.GoalLifecyclePayload;
import dev.mikoto2000.rei.goal.GoalRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 目標振り返りを保存・検索するための窓口。
 *
 * <p>Append-only observations and conservative review suggestions, separate from validated
 * memories.
 */
@Repository
public class GoalReflectionRepository {
  /**
   * 作業の振り返りで受け渡す項目を表す。
   *
   * @param id 識別子
   * @param projectId 所属プロジェクトの識別子
   * @param sessionId 会話の識別子
   * @param goalId 目標識別子
   * @param runId 実行の識別子
   * @param goalStatus 目標状態
   * @param expectedFile 期待する内容ファイル
   * @param expectedSha256 期待する内容内容の同一性を照合するSHA-256ハッシュ
   * @param actual 実際の
   * @param actualReason 実際の理由
   * @param gap 間隔
   * @param nextAction 次操作
   * @param sourceEvent 取得元イベント
   * @param createdAt 作成日時
   */
  public record Item(
      String id,
      String projectId,
      String sessionId,
      String goalId,
      String runId,
      String goalStatus,
      String expectedFile,
      String expectedSha256,
      String actual,
      String actualReason,
      String gap,
      String nextAction,
      String sourceEvent,
      Instant createdAt) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、GoalReflectionRepositoryを初期化する。 */
  public GoalReflectionRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    db = JdbcClient.create(source);
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS goal_reflections(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,goal TEXT NOT NULL,run TEXT NOT NULL,status TEXT NOT NULL,expected_file TEXT NOT NULL,expected_digest TEXT NOT NULL,actual TEXT NOT NULL,actual_reason TEXT NOT NULL,gap TEXT NOT NULL,next_action TEXT NOT NULL,source_event TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(project,goal,run,status))")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS goal_reflections_project ON goal_reflections(project,created)")
        .update();
  }

  /** 行。 */
  private static final org.springframework.jdbc.core.RowMapper<Item> ROW =
      (rs, n) ->
          new Item(
              rs.getString("id"),
              rs.getString("project"),
              rs.getString("session"),
              rs.getString("goal"),
              rs.getString("run"),
              rs.getString("status"),
              rs.getString("expected_file"),
              rs.getString("expected_digest"),
              rs.getString("actual"),
              rs.getString("actual_reason"),
              rs.getString("gap"),
              rs.getString("next_action"),
              rs.getString("source_event"),
              Instant.ofEpochMilli(rs.getLong("created")));

  /** 後から参照できるよう、指定された情報を保存する。 */
  Item save(
      GoalRepository.Goal goal,
      AgentEvent source,
      GoalLifecyclePayload snapshot,
      String actual,
      String reason,
      String gap,
      String nextAction) {
    // 別のプロジェクトの情報を、この作業の対象として扱わせない。
    if (!goal.projectId().equals(source.projectId())
        || !goal.sessionId().equals(source.sessionId())
        || !goal.id().equals(snapshot.goalId()))
      throw new IllegalArgumentException("Reflection source ownership does not match its Goal");
    String run = source.runId() == null ? "" : source.runId();
    db.sql("INSERT OR IGNORE INTO goal_reflections VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        .params(
            "reflection-" + UUID.randomUUID(),
            goal.projectId(),
            goal.sessionId(),
            goal.id(),
            run,
            snapshot.status(),
            expectedFiles(goal),
            expectedDigests(goal),
            actual,
            reason,
            gap,
            nextAction,
            source.id(),
            clock.millis())
        .update();
    return db.sql(
            "SELECT * FROM goal_reflections WHERE project=? AND goal=? AND run=? AND status=?")
        .params(goal.projectId(), goal.id(), run, snapshot.status())
        .query(ROW)
        .single();
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Item> list(String project) {
    return db.sql(
            "SELECT * FROM goal_reflections WHERE project=? ORDER BY created DESC,id LIMIT 256")
        .param(project)
        .query(ROW)
        .list();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Item get(String project, String id) {
    return db.sql("SELECT * FROM goal_reflections WHERE project=? AND id=?")
        .params(project, id)
        .query(ROW)
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Reflection not found in this Project"));
  }

  /** 条件に合う対象を選ぶため、判断基準一覧との一致を判定する。 */
  public boolean matchesCriteria(Item item, GoalRepository.Goal goal) {
    return item.expectedFile().equals(expectedFiles(goal))
        && item.expectedSha256().equals(expectedDigests(goal));
  }

  // Multiple criteria are ordered JSON arrays; single-file observations retain their original
  // representation.
  /** 作業の振り返りで用いる期待する内容ファイル一覧を返す。 */
  private String expectedFiles(GoalRepository.Goal goal) {
    return goal.criteria().size() == 1
        ? goal.relativeFile()
        : json(goal.criteria().stream().map(GoalRepository.FileCriterion::relativeFile).toList());
  }

  /** 作業の振り返りで用いる期待する内容Digestsを返す。 */
  private String expectedDigests(GoalRepository.Goal goal) {
    if (goal.criteria().stream().anyMatch(GoalRepository.FileCriterion::jsonCriterion))
      return json(goal.criteria());
    return goal.criteria().size() == 1
        ? goal.sha256()
        : json(goal.criteria().stream().map(GoalRepository.FileCriterion::sha256).toList());
  }

  /** 作業の振り返りで用いる保存・受け渡しに使うJSON変換を返す。 */
  private String json(Object value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
