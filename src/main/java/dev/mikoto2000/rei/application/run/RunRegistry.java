package dev.mikoto2000.rei.application.run;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 実行状態を一貫した記録として管理し、再起動後も状態を照会できるようにする。
 *
 * <p>Monitor-protected transitions publish an immutable snapshot of status and metadata together.
 */
public final class RunRegistry implements AutoCloseable {
  /** 受付台帳。 */
  private dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions;

  /** 実行の受付と状態管理で使用する受付台帳を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setAdmissions(dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions) {
    this.admissions = admissions;
  }

  /** 実行と cleanup 中の終端イベントは RunService が一度だけ確定する。 */
  public void beginExecution(String runId) {
    if (admissions != null) admissions.deferTerminalEvents(runId, true);
  }

  /** 実行所有者による終端確定後、通常のイベント購読へ戻す。 */
  public void endExecution(String runId) {
    if (admissions != null) admissions.deferTerminalEvents(runId, false);
  }

  /** 終了後の実行記録を保持する期間。 */
  public static final Duration RETENTION = Duration.ofMinutes(30);

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 実行識別子ごとの、現在の状態と実行文脈の記録。 */
  private final Map<String, RunSnapshot> runs = new LinkedHashMap<>();

  /** データベースへのアクセス。 */
  private final org.springframework.jdbc.core.simple.JdbcClient db;

  /** 起動単位の識別子。 */
  private final String instance = UUID.randomUUID().toString();

  /** 保存時のJSON。更新の競合を検出するため、次回保存時に元の内容と照合する。 */
  private final Map<String, String> documents = new HashMap<>();

  /** このプロセスが所有し、終了時に状態の整理を必要とする実行の識別子。 */
  private final Set<String> owned = new HashSet<>();

  /** 永続記録から復元した実行の識別子。 */
  private final Set<String> restored = new HashSet<>();

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final com.fasterxml.jackson.databind.ObjectMapper json =
      new com.fasterxml.jackson.databind.ObjectMapper()
          .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

  /**
   * 実行の受付と状態管理で受け渡す保存された実行を表す。
   *
   * @param version 形式のバージョン
   * @param runId 実行の識別子
   * @param sessionId 会話の識別子
   * @param root 作業ルート
   * @param projectId 所属プロジェクトの識別子
   * @param source 取得元
   * @param mode 動作モード
   * @param status 状態
   * @param startedAt 開始日時
   * @param completedAt 完了日時
   * @param failure 失敗情報
   * @param ownerPid 所有プロセスの識別子
   * @param ownerStart 所有プロセスの開始日時
   * @param ownerInstance 所有する起動単位の識別子
   * @param childOrigin 子の実行を開始した経路
   * @param voiceInput 音声入力からの依頼かどうか
   */
  private record StoredRun(
      int version,
      String runId,
      String sessionId,
      String root,
      String projectId,
      AgentRunContext.RequestSource source,
      AgentRunContext.Mode mode,
      RunStatus status,
      Instant startedAt,
      Instant completedAt,
      RunFailure failure,
      long ownerPid,
      String ownerStart,
      String ownerInstance,
      RunSnapshot.ChildOrigin childOrigin,
      boolean voiceInput) {
    /** 実行の受付と状態管理で受け渡す情報を、指定された内容から作成する。 */
    static StoredRun of(RunSnapshot value, String instance) {
      var c = value.context();
      return new StoredRun(
          1,
          c.runId(),
          c.conversationId(),
          c.projectRoot().toString(),
          c.projectId(),
          c.requestSource(),
          c.mode(),
          value.status(),
          value.startedAt(),
          value.completedAt(),
          value.failure(),
          instance == null ? 0 : ProcessHandle.current().pid(),
          instance == null
              ? null
              : ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null),
          instance,
          value.childOrigin(),
          c.voiceInput());
    }

    /** 保存したプロセス識別子と開始日時が一致する所有者が、現在も生存しているか確認する。 */
    boolean ownerAlive() {
      // プロセス番号の再利用を別の所有者と誤認しないよう、開始日時も一致することを求める。
      return ownerPid > 0
          && ownerStart != null
          && ProcessHandle.of(ownerPid)
              .filter(ProcessHandle::isAlive)
              .flatMap(handle -> handle.info().startInstant())
              .map(instant -> instant.toString().equals(ownerStart))
              .orElse(false);
    }

    /** 保存形式のバージョンを確認し、照会に使う実行状態へ復元する。 */
    RunSnapshot snapshot() {
      if (version != 1 || status == null) throw new IllegalStateException("Unsupported Run schema");
      return new RunSnapshot(
          new AgentRunContext(
              runId, sessionId, java.nio.file.Path.of(root), projectId, source, mode, voiceInput),
          status,
          startedAt,
          completedAt,
          failure,
          childOrigin);
    }
  }

  /** 実行の受付と状態管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public RunRegistry(Clock clock) {
    this(clock, null);
  }

  /** 指定された値や利用先を使って、RunRegistryを初期化する。 */
  public RunRegistry(Clock clock, javax.sql.DataSource source) {
    this.clock = clock;
    this.db =
        source == null ? null : org.springframework.jdbc.core.simple.JdbcClient.create(source);
    if (db == null) return;
    db.sql(
            "CREATE TABLE IF NOT EXISTS rei_run_registry (run_id TEXT PRIMARY KEY, snapshot TEXT NOT NULL)")
        .update();
    for (String document :
        db.sql("SELECT snapshot FROM rei_run_registry ORDER BY run_id")
            .query(String.class)
            .list()) {
      try {
        if (document.length() > 20000)
          throw new IllegalStateException("Run snapshot capacity exceeded");
        var stored = json.readValue(document, StoredRun.class);
        var value = stored.snapshot();
        documents.put(value.context().runId(), document);
        // 所有者が失われた実行を成功・失敗と決め付けず、確認が必要な結果不明の状態へ移す。
        if (!value.status().isTerminal() && !value.status().isWaiting() && !stored.ownerAlive()) {
          value = unknown(value);
          save(value);
        }
        runs.put(value.context().runId(), value);
        restored.add(value.context().runId());
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Cannot restore Run registry", error);
      }
    }
    purgeExpired();
  }

  /** 現在の実行状態を保存する。他の所有者による更新があれば競合として扱う。 */
  private void save(RunSnapshot value) {
    save(value, instance);
  }

  /** 現在の実行状態を保存する。他の所有者による更新があれば競合として扱う。 */
  private void save(RunSnapshot value, String ownerInstance) {
    if (db == null) return;
    try {
      String document = json.writeValueAsString(StoredRun.of(value, ownerInstance));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (document.length() > 20000)
        throw new IllegalArgumentException("Run snapshot capacity exceeded");
      // 最後に確認した保存内容と照合し、別の所有者が更新した状態を上書きしない。
      String id = value.context().runId(), previous = documents.get(id);
      if (previous == null)
        db.sql("INSERT INTO rei_run_registry(run_id,snapshot) VALUES(:id,:snapshot)")
            .param("id", id)
            .param("snapshot", document)
            .update();
      else if (db.sql(
                  "UPDATE rei_run_registry SET snapshot=:snapshot WHERE run_id=:id AND snapshot=:previous")
              .param("id", id)
              .param("snapshot", document)
              .param("previous", previous)
              .update()
          != 1) throw new dev.mikoto2000.rei.application.state.OperationConflictException();
      documents.put(id, document);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot save Run registry", error);
    }
  }

  /** 保存時の状態が一致する場合に限り、実行の永続記録を削除する。 */
  private void delete(String runId) {
    if (db != null && documents.containsKey(runId)) {
      if (db.sql("DELETE FROM rei_run_registry WHERE run_id=:id AND snapshot=:previous")
              .param("id", runId)
              .param("previous", documents.get(runId))
              .update()
          != 1) throw new dev.mikoto2000.rei.application.state.OperationConflictException();
      documents.remove(runId);
    }
  }

  /** 所有者を失った実行を結果不明として記録し、明示的な再開前の確認を促す。 */
  private RunSnapshot unknown(RunSnapshot value) {
    return new RunSnapshot(
        value.context(),
        RunStatus.UNKNOWN,
        value.startedAt(),
        clock.instant(),
        new RunFailure(
            "OwnerLost", "Execution result unknown; inspect checkpoint before explicit resume"),
        value.childOrigin());
  }

  /** 終了するプロセスの実行を整理し、待機中の実行と結果不明の実行を保存する。 */
  @Override
  public synchronized void close() {
    if (db == null) return;
    for (String run : owned) {
      var value = runs.get(run);
      // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
      // プロセス終了で継続できない実行は結果不明にし、承認などの待機状態は再開のために残す。
      if (value != null && !value.status().isTerminal() && !value.status().isWaiting()) {
        value = unknown(value);
        save(value);
        runs.put(run, value);
      } else if (value != null && value.status().isWaiting()) save(value, null);
    }
    owned.clear();
  }

  /**
   * 実行の受付と状態管理に関する記憶の削除を処理する。
   *
   * <p>Roll back a synchronous submission failure before returning acceptance to the caller.
   */
  public synchronized void forget(String runId) {
    var value = runs.get(runId);
    // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
    if (db != null && value != null && !value.status().isTerminal() && !owned.contains(runId))
      throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    delete(runId);
    runs.remove(runId);
    owned.remove(runId);
    restored.remove(runId);
  }

  /** 後から対象を参照できるよう、実行登録台帳を登録する。 */
  public synchronized void register(AgentRunContext context) {
    if (runs.containsKey(context.runId())) throw new IllegalArgumentException("Duplicate run");
    var value = new RunSnapshot(context, RunStatus.QUEUED, null, null, null);
    save(value);
    runs.put(context.runId(), value);
    owned.add(context.runId());
  }

  /**
   * 後から対象を参照できるよう、子を登録する。
   *
   * <p>Delegation retains the existing child Run, with the human owner's Session as metadata.
   */
  public synchronized void registerChild(
      AgentRunContext child, AgentRunContext parent, String agent) {
    registerChild(child, parent, agent, null);
  }

  /** 後から対象を参照できるよう、子を登録する。 */
  public synchronized void registerChild(
      AgentRunContext child, AgentRunContext parent, String agent, String durableTaskId) {
    if (durableTaskId != null && !durableTaskId.matches("[A-Za-z0-9_-]{1,64}"))
      throw new IllegalArgumentException("Invalid durable child reference");
    if (!Objects.equals(child.projectId(), parent.projectId())
        || !child.projectRoot().equals(parent.projectRoot())
        || child.runId().equals(parent.runId())
        || parent.projectId() == null
        || agent == null
        || agent.isBlank()
        || agent.length() > 128) throw new IllegalArgumentException("Invalid child ownership");
    var owner = get(parent.runId());
    if (!owner.context().equals(parent)
        || !ownsExecution(parent.runId())
        || owner.status().isTerminal())
      throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    if (runs.containsKey(child.runId())) throw new IllegalArgumentException("Duplicate run");
    var origin =
        new RunSnapshot.ChildOrigin(
            parent.runId(),
            owner.childOrigin() == null ? parent.conversationId() : owner.childOrigin().sessionId(),
            agent,
            durableTaskId);
    var value = new RunSnapshot(child, RunStatus.QUEUED, null, null, null, origin);
    save(value);
    runs.put(child.runId(), value);
    owned.add(child.runId());
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public synchronized RunSnapshot get(String runId) {
    if (admissions != null) {
      var value = admissions.get(runId);
      if (value.isPresent()) {
        var mirror = runs.get(runId);
        if (owned.contains(runId)
            && mirror != null
            && !mirror.status().isTerminal()
            && value.get().status().isTerminal()) {
          save(value.get());
          runs.put(runId, value.get());
        }
        return value.get();
      }
    }
    if (db != null && !owned.contains(runId)) {
      var document =
          db.sql("SELECT snapshot FROM rei_run_registry WHERE run_id=:id")
              .param("id", runId)
              .query(String.class)
              .optional();
      if (document.isEmpty()) {
        runs.remove(runId);
        documents.remove(runId);
        restored.remove(runId);
        throw new RunNotFoundException();
      }
      if (!document.get().equals(documents.get(runId))
          || !runs.containsKey(runId)
          || !runs.get(runId).status().isTerminal()) {
        try {
          // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
          if (document.get().length() > 20000)
            throw new IllegalStateException("Run snapshot capacity exceeded");
          var stored = json.readValue(document.get(), StoredRun.class);
          var refreshed = stored.snapshot();
          if (!runId.equals(refreshed.context().runId()))
            throw new IllegalStateException("Run identity mismatch");
          documents.put(runId, document.get());
          // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
          if (!refreshed.status().isTerminal()
              && !refreshed.status().isWaiting()
              && !stored.ownerAlive()) {
            refreshed = unknown(refreshed);
            save(refreshed);
          }
          runs.put(runId, refreshed);
          restored.add(runId);
        } catch (java.io.IOException error) {
          throw new IllegalStateException("Cannot refresh Run registry", error);
        }
      }
    }
    var snapshot = runs.get(runId);
    if (snapshot == null) throw new RunNotFoundException();
    return snapshot;
  }

  /** Streaming checks only its target; normal lookups still perform global retention cleanup. */
  public synchronized RunStatus streamStatus(String runId) {
    var snapshot = get(runId);
    if (snapshot.completedAt() != null
        && !clock.instant().isBefore(snapshot.completedAt().plus(RETENTION)))
      throw new RunNotFoundException();
    return snapshot.status();
  }

  /** 実行の受付と状態管理において、状態の遷移に該当するか判定する。 */
  public synchronized boolean transition(String runId, RunStatus next, RunFailure failure) {
    var current = get(runId);
    // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
    if (current.status().isTerminal() || next == RunStatus.QUEUED || next == current.status())
      return false;
    if (db != null && !owned.contains(runId))
      throw new dev.mikoto2000.rei.application.state.OperationConflictException();
    if (current.status() == RunStatus.QUEUED
        && next != RunStatus.RUNNING
        && next != RunStatus.CANCELLED) return false;
    var value =
        new RunSnapshot(
            current.context(),
            next,
            next == RunStatus.RUNNING && current.startedAt() == null
                ? clock.instant()
                : current.startedAt(),
            next.isTerminal() ? clock.instant() : null,
            next == RunStatus.FAILED || next == RunStatus.UNKNOWN ? failure : null,
            current.childOrigin());
    save(value);
    runs.put(runId, value);
    if (admissions != null) admissions.transition(runId, next);
    return true;
  }

  /** 実行の受付と状態管理で用いる保持期限を過ぎた情報の削除期限切れを返す。 */
  public synchronized List<String> purgeExpired() {
    var expired =
        runs.entrySet().stream()
            .filter(
                entry ->
                    entry.getValue().completedAt() != null
                        && !clock
                            .instant()
                            .isBefore(entry.getValue().completedAt().plus(RETENTION)))
            .map(Map.Entry::getKey)
            .toList();
    expired.forEach(this::forget);
    return expired;
  }

  /** 依頼された操作を進めるため、識別子一覧を実行する。 */
  public synchronized Set<String> runIds() {
    return db == null
        ? Set.copyOf(runs.keySet())
        : Set.copyOf(db.sql("SELECT run_id FROM rei_run_registry").query(String.class).list());
  }

  /**
   * 実行の受付と状態管理において、復元済みに該当するか判定する。
   *
   * <p>Restored metadata has no event subscription history in this server instance.
   */
  public synchronized boolean restored(String runId) {
    return restored.contains(runId)
        || (admissions != null && admissions.get(runId).isPresent() && !runs.containsKey(runId));
  }

  /** 実行の受付と状態管理において、owns実行に該当するか判定する。 */
  public synchronized boolean ownsExecution(String runId) {
    return db == null || owned.contains(runId);
  }

  /**
   * 対象を処理する担当を決めるため、待機中の利用権を確保する。
   *
   * <p>Only an exact durable waiting owner can be adopted after its previous process exited.
   */
  public synchronized boolean claimWaiting(AgentRunContext context) {
    var current = get(context.runId());
    if (!current.status().isWaiting() || !current.context().equals(context)) return false;
    if (db != null && !owned.contains(context.runId())) {
      var document =
          db.sql("SELECT snapshot FROM rei_run_registry WHERE run_id=:id")
              .param("id", context.runId())
              .query(String.class)
              .optional();
      if (document.isEmpty()) return false;
      try {
        var stored = json.readValue(document.get(), StoredRun.class);
        if (stored.ownerAlive()) return false;
      } catch (java.io.IOException error) {
        throw new IllegalStateException("Cannot inspect waiting Run owner", error);
      }
      if (admissions != null && !admissions.claimWaiting(context)) return false;
      documents.put(context.runId(), document.get());
      save(current);
      owned.add(context.runId());
      restored.remove(context.runId());
    }
    return true;
  }
}
