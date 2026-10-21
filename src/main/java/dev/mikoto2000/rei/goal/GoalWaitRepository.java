package dev.mikoto2000.rei.goal;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 目標待機を保存・検索するための窓口。
 *
 * <p>Durable binding between existing Goal, Dependency, Scheduler and Checkpoint identities.
 */
@org.springframework.stereotype.Repository
public class GoalWaitRepository {
  /**
   * 目標の達成管理で受け渡す状態の記録を表す。
   *
   * @param progress 進捗
   * @param completion 完了条件
   * @param condition 条件
   * @param checkpointTask 再開用の記録タスク
   * @param checkpointRevision 再開用の記録改訂番号
   * @param operations 操作一覧
   * @param resumeInfo 再開付随情報
   */
  public record Snapshot(
      GoalCompletionProgress progress,
      GoalCompletionGate.Definition completion,
      DependencySpec condition,
      String checkpointTask,
      long checkpointRevision,
      List<PersistentCheckpoint.Operation> operations,
      String resumeInfo) {
    /** 受け取った情報からSnapshotを作成する。 */
    public Snapshot {
      operations = List.copyOf(operations);
    }
  }

  /**
   * 目標の達成管理で受け渡す待機を表す。
   *
   * @param id 識別子
   * @param project プロジェクト
   * @param goalId 目標識別子
   * @param runId 実行の識別子
   * @param attempts 試行回数
   * @param reason 理由
   * @param dependencyId 依存条件識別子
   * @param scheduleId 実行予定識別子
   * @param state 状態
   * @param lastObservation 直近観測内容
   * @param version 形式のバージョン
   * @param resumedRunId 再開済み実行の識別子
   * @param snapshot 状態の記録
   */
  public record Wait(
      String id,
      String project,
      String goalId,
      String runId,
      int attempts,
      String reason,
      String dependencyId,
      String scheduleId,
      String state,
      String lastObservation,
      long version,
      String resumedRunId,
      Snapshot snapshot) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新をまとめて確定するための窓口。 */
  private final TransactionTemplate tx;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** 指定された値や利用先を使って、GoalWaitRepositoryを初期化する。 */
  public GoalWaitRepository(
      @Qualifier("memoryConsolidationDataSource") DataSource source, Clock clock) {
    db = JdbcClient.create(source);
    tx = new TransactionTemplate(new DataSourceTransactionManager(source));
    this.clock = clock;
    db.sql(
            "CREATE TABLE IF NOT EXISTS agent_goal_waits(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,project TEXT NOT NULL,goal TEXT NOT NULL,run TEXT,attempts INTEGER NOT NULL,reason TEXT NOT NULL,dependency TEXT NOT NULL,schedule TEXT NOT NULL UNIQUE,state TEXT NOT NULL,observed TEXT NOT NULL,version INTEGER NOT NULL,resumed_run TEXT,snapshot TEXT NOT NULL,created INTEGER NOT NULL)")
        .update();
    db.sql(
            "CREATE UNIQUE INDEX IF NOT EXISTS agent_goal_wait_active ON agent_goal_waits(project,goal) WHERE state IN ('WAITING','BLOCKED','RESUMING')")
        .update();
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private static String encode(Snapshot snapshot) {
    try {
      String result = JSON.writeValueAsString(snapshot);
      if (result.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 131072)
        throw new IllegalArgumentException("Wait snapshot exceeds 128KiB");
      return result;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Wait snapshot unavailable", e);
    }
  }

  /** 受け渡し先の形式に合わせて、定義を変換する。 */
  private static String encodeDefinition(GoalCompletionGate.Definition definition) {
    try {
      return JSON.writeValueAsString(definition);
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException(e);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private static Snapshot decode(String json) {
    try {
      return JSON.readValue(json, Snapshot.class);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Wait snapshot unavailable", e);
    }
  }

  /** 行。 */
  private final org.springframework.jdbc.core.RowMapper<Wait> row =
      (rs, n) ->
          new Wait(
              rs.getString("id"),
              rs.getString("project"),
              rs.getString("goal"),
              rs.getString("run"),
              rs.getInt("attempts"),
              rs.getString("reason"),
              rs.getString("dependency"),
              rs.getString("schedule"),
              rs.getString("state"),
              rs.getString("observed"),
              rs.getLong("version"),
              rs.getString("resumed_run"),
              decode(rs.getString("snapshot")));

  /** 指定された内容から新しい対象を作成する。 */
  public Wait create(
      GoalRepository.Goal goal,
      String dependency,
      String reason,
      Snapshot snapshot,
      Supplier<String> schedule) {
    String encoded = encode(snapshot);
    return tx.execute(
        status -> {
          if (active(goal.projectId(), goal.id()))
            throw new IllegalStateException("Goal already has an active wait");
          if (db.sql(
                      "SELECT COUNT(*) FROM agent_goal_waits WHERE project=? AND state IN ('WAITING','BLOCKED','RESUMING')")
                  .param(goal.projectId())
                  .query(Integer.class)
                  .single()
              >= 256) throw new IllegalStateException("Goal wait capacity reached");
          if (db.sql(
                      "UPDATE agent_goals SET status='PAUSED',reason='dependency_wait' WHERE project=? AND id=? AND run IS ? AND attempts=? AND status=? AND status IN ('READY','PAUSED','FAILED','WAITING_APPROVAL','BLOCKED') AND completion_json IS ?")
                  .params(
                      goal.projectId(),
                      goal.id(),
                      goal.currentRunId(),
                      goal.attempts(),
                      goal.status(),
                      goal.completion() == null ? null : encodeDefinition(goal.completion()))
                  .update()
              != 1) throw new IllegalStateException("Goal changed before wait");
          db.sql(
                  "INSERT INTO agent_goal_history(goal,status,reason,timestamp) VALUES(?,'PAUSED','dependency_wait',?)")
              .params(goal.id(), clock.millis())
              .update();
          String id = UUID.randomUUID().toString(), timer = schedule.get();
          db.sql(
                  "INSERT INTO agent_goal_waits(id,project,goal,run,attempts,reason,dependency,schedule,state,observed,version,snapshot,created) VALUES(?,?,?,?,?,?,?,?,'WAITING','not_observed',1,?,?)")
              .params(
                  id,
                  goal.projectId(),
                  goal.id(),
                  goal.currentRunId(),
                  goal.attempts(),
                  reason,
                  dependency,
                  timer,
                  encoded,
                  clock.millis())
              .update();
          return byId(goal.projectId(), id);
        });
  }

  /** 目標の達成管理で用いる別識別子を返す。 */
  private Wait byId(String project, String id) {
    return db.sql("SELECT * FROM agent_goal_waits WHERE project=? AND id=?")
        .params(project, id)
        .query(row)
        .single();
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Wait get(String project, String goal) {
    return db.sql(
            "SELECT * FROM agent_goal_waits WHERE project=? AND goal=? ORDER BY sequence DESC LIMIT 1")
        .params(project, goal)
        .query(row)
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Goal wait not found"));
  }

  /** 目標の達成管理で用いる別実行予定を返す。 */
  public Optional<Wait> bySchedule(String project, String id) {
    return db.sql("SELECT * FROM agent_goal_waits WHERE project=? AND schedule=?")
        .params(project, id)
        .query(row)
        .optional();
  }

  /** 目標の達成管理において、活動中に該当するか判定する。 */
  public boolean active(String project, String goal) {
    return db.sql(
                "SELECT COUNT(*) FROM agent_goal_waits WHERE project=? AND goal=? AND state IN ('WAITING','BLOCKED','RESUMING')")
            .params(project, goal)
            .query(Integer.class)
            .single()
        > 0;
  }

  /** 目標の達成管理で用いる状態の遷移を返す。 */
  public Wait transition(Wait expected, String state, String observed, String newRun) {
    if (!Set.of("WAITING", "BLOCKED", "RESUMING", "RESUMED", "CANCELLED").contains(state)
        || observed == null
        || !observed.matches("[a-z_]{1,80}"))
      throw new IllegalArgumentException("Structured wait transition required");
    if (db.sql(
                "UPDATE agent_goal_waits SET state=?,observed=?,resumed_run=?,version=version+1 WHERE project=? AND id=? AND version=? AND state=?")
            .params(
                state,
                observed,
                newRun,
                expected.project(),
                expected.id(),
                expected.version(),
                expected.state())
            .update()
        != 1) throw new IllegalStateException("Goal wait changed");
    return byId(expected.project(), expected.id());
  }
}
