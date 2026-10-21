package dev.mikoto2000.rei.subagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.CredentialRedactor;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 永続化された補助エージェントを保存・検索するための窓口。
 *
 * <p>Durable metadata for the existing child runner. Reservations and Tool starts precede
 * execution.
 */
public final class DurableSubAgentRepository {
  /**
   * 補助エージェントへの委譲で受け渡す操作を表す。
   *
   * @param id 識別子
   * @param tool ツール
   * @param argumentsHash 引数ハッシュ値
   * @param status 状態
   * @param reconciliation 状態の照合結果
   */
  public record Operation(
      String id, String tool, String argumentsHash, String status, String reconciliation) {}

  /**
   * 補助エージェントへの委譲で受け渡す再開用の記録を表す。
   *
   * @param version 形式のバージョン
   * @param id 識別子
   * @param revision 改訂番号
   * @param project プロジェクト
   * @param root 作業ルート
   * @param session 会話
   * @param parentRun 親実行
   * @param agent エージェント
   * @param task タスク
   * @param context 文脈
   * @param baseline 比較の基準
   * @param status 状態
   * @param run 実行
   * @param maxCalls 最大呼び出し回数
   * @param maxTokens トークン数の上限
   * @param consumedCalls 消費量呼び出し回数
   * @param consumedTokens 消費量トークン数
   * @param usageUnknown 使用状況不明
   * @param modelPending モデル待機中
   * @param pendingModels 待機中モデル一覧
   * @param operations 操作一覧
   * @param result 結果
   * @param resultHash 結果ハッシュ値
   * @param updated 更新
   * @param ownerPid 所有プロセスの識別子
   * @param ownerStart 所有プロセスの開始日時
   * @param kind 種類
   * @param graphId 関連図識別子
   */
  public record Checkpoint(
      int version,
      String id,
      long revision,
      String project,
      String root,
      String session,
      String parentRun,
      String agent,
      String task,
      String context,
      String baseline,
      String status,
      String run,
      int maxCalls,
      long maxTokens,
      int consumedCalls,
      long consumedTokens,
      boolean usageUnknown,
      boolean modelPending,
      int pendingModels,
      List<Operation> operations,
      String result,
      String resultHash,
      Instant updated,
      long ownerPid,
      String ownerStart,
      String kind,
      String graphId) {
    /** 受け取った情報からCheckpointを作成する。 */
    public Checkpoint {
      kind = kind == null ? "CHILD" : kind;
      if (!Set.of("CHILD", "GRAPH").contains(kind))
        throw new IllegalArgumentException("Invalid child checkpoint kind");
      if (pendingModels < 0 || pendingModels > 1000)
        throw new IllegalArgumentException("Invalid pending model reservations");
      if (modelPending && pendingModels == 0) pendingModels = 1;
      modelPending = pendingModels > 0;
      operations = List.copyOf(operations);
    }
  }

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 一連の更新。 */
  private final TransactionTemplate transaction;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** バイト数の上限。 */
  private final int maxBytes;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DurableSubAgentRepository(DataSource source, Clock clock) {
    this(source, clock, 8388608);
  }

  /** 指定された値や利用先を使って、DurableSubAgentRepositoryを初期化する。 */
  DurableSubAgentRepository(DataSource source, Clock clock, int maxBytes) {
    if (maxBytes < 1024 || maxBytes > 8388608)
      throw new IllegalArgumentException("Invalid durable child storage limit");
    this.maxBytes = maxBytes;
    this.db = JdbcClient.create(source);
    this.clock = clock;
    transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    db.sql(
            "CREATE TABLE IF NOT EXISTS subagent_checkpoints(id TEXT PRIMARY KEY,revision INTEGER NOT NULL,snapshot TEXT NOT NULL)")
        .update();
    for (var saved :
        db.sql("SELECT snapshot FROM subagent_checkpoints ORDER BY id LIMIT 1025")
            .query(String.class)
            .list()) {
      var state = decode(saved);
      if (state.status().equals("RUNNING") && !alive(state)) ownerLost(state.id(), state.run());
    }
  }

  /** 指定された内容から新しい対象を作成する。 */
  public synchronized Checkpoint create(
      AgentRunContext owner,
      String agent,
      String task,
      String context,
      int calls,
      long tokens,
      String baseline) {
    return createPrepared(
        owner,
        UUID.randomUUID().toString(),
        "CHILD",
        null,
        agent,
        task,
        context,
        calls,
        tokens,
        baseline);
  }

  /** 新たな対象を利用できるよう、準備済みを作成する。 */
  synchronized Checkpoint createPrepared(
      AgentRunContext owner,
      String id,
      String kind,
      String graphId,
      String agent,
      String task,
      String context,
      int calls,
      long tokens,
      String baseline) {
    if (id == null
        || !id.matches("[0-9a-f-]{36}")
        || graphId != null && !graphId.matches("[0-9a-f-]{36}"))
      throw new IllegalArgumentException("Invalid graph/child identity");
    identity(owner);
    if (agent == null
        || !agent.matches("[a-z][a-z0-9-]{0,63}")
        || task == null
        || task.isBlank()
        || task.length() > 16384
        || context != null && context.length() > 32768
        || calls < 1
        || calls > 1000
        || tokens < 0
        || baseline == null
        || baseline.length() > 256)
      throw new IllegalArgumentException("Invalid durable child request");
    var state =
        new Checkpoint(
            1,
            id,
            0,
            owner.projectId(),
            root(owner),
            owner.conversationId(),
            owner.runId(),
            agent,
            CredentialRedactor.redact(task),
            context == null ? null : CredentialRedactor.redact(context),
            baseline,
            "QUEUED",
            null,
            calls,
            tokens,
            0,
            0,
            false,
            false,
            0,
            List.of(),
            null,
            null,
            clock.instant(),
            0,
            null,
            kind,
            graphId);
    String encoded = encode(state);
    return transaction.execute(
        tx -> {
          if (db.sql("SELECT COUNT(*) FROM subagent_checkpoints").query(Long.class).single() >= 1024
              || db.sql(
                              "SELECT COALESCE(SUM(length(CAST(snapshot AS BLOB))),0) FROM subagent_checkpoints")
                          .query(Long.class)
                          .single()
                      + encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  > maxBytes)
            throw new IllegalArgumentException("Durable child storage capacity reached");
          db.sql("INSERT INTO subagent_checkpoints(id,revision,snapshot) VALUES(?,0,?)")
              .params(state.id(), encode(state))
              .update();
          return state;
        });
  }

  /** 補助エージェントへの委譲で用いる一括反映を返す。 */
  synchronized <T> T atomic(java.util.function.Supplier<T> action) {
    return transaction.execute(tx -> action.get());
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public Checkpoint get(AgentRunContext owner, String id) {
    identity(owner);
    var state = load(id);
    if (!state.project().equals(owner.projectId())
        || !state.root().equals(root(owner))
        || !state.session().equals(owner.conversationId()))
      throw new IllegalArgumentException("Child belongs to another human Project/root/session");
    return state;
  }

  /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
  public List<Checkpoint> list(AgentRunContext owner, int offset, int limit) {
    identity(owner);
    if (offset < 0 || offset > 1024 || limit < 1 || limit > 100)
      throw new IllegalArgumentException("Invalid child page");
    return db
        .sql(
            "SELECT snapshot FROM subagent_checkpoints WHERE json_extract(snapshot,'$.project')=? AND json_extract(snapshot,'$.root')=? AND json_extract(snapshot,'$.session')=? ORDER BY id LIMIT ? OFFSET ?")
        .params(owner.projectId(), root(owner), owner.conversationId(), limit, offset)
        .query(String.class)
        .list()
        .stream()
        .map(this::decode)
        .toList();
  }

  /** 対象を処理する担当を決めるため、永続化された補助エージェント保存と検索の窓口の利用権を確保する。 */
  public synchronized Checkpoint claim(
      AgentRunContext owner, String id, long revision, String run) {
    var saved = get(owner, id);
    if (saved.revision() != revision
        || run == null
        || run.isBlank()
        || run.length() > 128
        || !Set.of("QUEUED", "UNKNOWN", "FAILED", "CANCELLED", "TIMEOUT").contains(saved.status())
        || saved.operations().stream()
            .anyMatch(o -> Set.of("STARTED", "UNKNOWN").contains(o.status()))
        || exhausted(saved))
      throw new IllegalArgumentException(
          "Child requires current revision, reconciliation and remaining budget");
    return update(
        saved,
        s ->
            copy(
                s,
                "RUNNING",
                run,
                s.consumedCalls(),
                s.consumedTokens(),
                s.usageUnknown(),
                s.operations(),
                null,
                null,
                ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(null)));
  }

  /** 補助エージェントへの委譲において、予約に該当するか判定する。 */
  public synchronized boolean reserve(String id, String run) {
    var saved = owned(id, run);
    if (exhausted(saved)) return false;
    update(
        saved,
        s ->
            pending(
                copy(
                    s,
                    s.status(),
                    run,
                    s.consumedCalls() + 1,
                    s.consumedTokens(),
                    s.usageUnknown(),
                    s.operations(),
                    s.result(),
                    s.resultHash(),
                    s.ownerPid(),
                    s.ownerStart()),
                true));
    return true;
  }

  /** 補助エージェントへの委譲に関するトークン数を処理する。 */
  public synchronized void tokens(String id, String run, Integer tokens) {
    var saved = owned(id, run);
    if (tokens != null && tokens < 0) throw new IllegalArgumentException("Invalid token usage");
    update(
        saved,
        s ->
            pending(
                copy(
                    s,
                    s.status(),
                    run,
                    s.consumedCalls(),
                    tokens == null ? s.consumedTokens() : Math.addExact(s.consumedTokens(), tokens),
                    s.usageUnknown() || tokens == null || tokens == 0,
                    s.operations(),
                    s.result(),
                    s.resultHash(),
                    s.ownerPid(),
                    s.ownerStart()),
                false));
  }

  /** 補助エージェントへの委譲に関するツール開始を処理する。 */
  public synchronized void toolStarted(
      String id, String run, String operation, String tool, String hash) {
    var saved = owned(id, run);
    if (operation == null
        || operation.length() > 128
        || tool == null
        || tool.length() > 128
        || hash == null
        || hash.length() > 128
        || saved.operations().size() >= 64
        || saved.operations().stream().anyMatch(o -> o.id().equals(operation)))
      throw new IllegalArgumentException("Invalid or duplicate child Tool operation");
    var operations = new ArrayList<>(saved.operations());
    operations.add(new Operation(operation, tool, hash, "STARTED", null));
    update(
        saved,
        s ->
            copy(
                s,
                s.status(),
                run,
                s.consumedCalls(),
                s.consumedTokens(),
                s.usageUnknown(),
                operations,
                s.result(),
                s.resultHash(),
                s.ownerPid(),
                s.ownerStart()));
  }

  /** 補助エージェントへの委譲に関するツール完了を処理する。 */
  public synchronized void toolCompleted(String id, String run, String operation, boolean success) {
    var saved = owned(id, run);
    if (saved.operations().stream()
        .noneMatch(o -> o.id().equals(operation) && o.status().equals("STARTED")))
      throw new IllegalArgumentException("No started child Tool operation");
    var operations =
        saved.operations().stream()
            .map(
                o ->
                    o.id().equals(operation)
                        ? new Operation(
                            o.id(),
                            o.tool(),
                            o.argumentsHash(),
                            success ? "SUCCEEDED" : "UNKNOWN",
                            null)
                        : o)
            .toList();
    update(
        saved,
        s ->
            copy(
                s,
                s.status(),
                run,
                s.consumedCalls(),
                s.consumedTokens(),
                s.usageUnknown(),
                operations,
                s.result(),
                s.resultHash(),
                s.ownerPid(),
                s.ownerStart()));
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  public synchronized void complete(String id, String run, String status, String result) {
    var saved = owned(id, run);
    if (!Set.of("COMPLETED", "FAILED", "CANCELLED", "TIMEOUT", "UNKNOWN").contains(status)
        || result != null && result.length() > 65536)
      throw new IllegalArgumentException("Invalid child result");
    boolean unknown =
        saved.operations().stream()
            .anyMatch(o -> Set.of("STARTED", "UNKNOWN").contains(o.status()));
    String retained = result == null ? null : CredentialRedactor.redact(result);
    update(
        saved,
        s ->
            copy(
                s,
                unknown ? "UNKNOWN" : status,
                run,
                s.consumedCalls(),
                s.consumedTokens(),
                s.usageUnknown() || s.modelPending() && s.maxTokens() > 0,
                unknown(s.operations()),
                retained,
                retained == null ? null : hash(retained),
                0,
                null));
  }

  /** 補助エージェントへの委譲に関する所有者失われたを処理する。 */
  public synchronized void ownerLost(String id, String run) {
    var saved = owned(id, run);
    update(
        saved,
        s ->
            copy(
                s,
                "UNKNOWN",
                run,
                s.consumedCalls(),
                s.consumedTokens(),
                s.usageUnknown() || s.modelPending() && s.maxTokens() > 0,
                unknown(s.operations()),
                s.result(),
                s.resultHash(),
                0,
                null));
  }

  /** 現在の状態と記録を照合し、差異に応じて管理状態を整える。 */
  public synchronized Checkpoint reconcile(
      AgentRunContext owner,
      String id,
      long revision,
      String operation,
      String status,
      String note) {
    var saved = get(owner, id);
    if (owner.mode() != AgentRunContext.Mode.EXCLUSIVE)
      throw new IllegalArgumentException("Exclusive human Run required for reconciliation");
    if (saved.revision() != revision
        || !saved.status().equals("UNKNOWN")
        || !Set.of("SUCCEEDED", "FAILED").contains(status)
        || note == null
        || note.isBlank()
        || note.length() > 1000
        || saved.operations().stream()
            .noneMatch(o -> o.id().equals(operation) && o.status().equals("UNKNOWN")))
      throw new IllegalArgumentException(
          "Current unknown operation and explicit human observation required");
    var operations =
        saved.operations().stream()
            .map(
                o ->
                    o.id().equals(operation)
                        ? new Operation(
                            o.id(),
                            o.tool(),
                            o.argumentsHash(),
                            status,
                            CredentialRedactor.redact(note))
                        : o)
            .toList();
    return update(
        saved,
        s ->
            copy(
                s,
                s.status(),
                s.run(),
                s.consumedCalls(),
                s.consumedTokens(),
                s.usageUnknown(),
                operations,
                s.result(),
                s.resultHash(),
                0,
                null));
  }

  /** 補助エージェントへの委譲で用いる自分が所有する対象を返す。 */
  private Checkpoint owned(String id, String run) {
    var state = load(id);
    if (!state.status().equals("RUNNING") || !Objects.equals(state.run(), run))
      throw new IllegalArgumentException("Child execution owner changed");
    return state;
  }

  /** 保存された情報を読み込み、現在の処理で使える形にする。 */
  private Checkpoint load(String id) {
    if (id == null || !id.matches("[0-9a-f-]{36}"))
      throw new IllegalArgumentException("Invalid child ID");
    return decode(
        db.sql("SELECT snapshot FROM subagent_checkpoints WHERE id=?")
            .param(id)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Child not found")));
  }

  /** 指定された変更を、管理中の情報へ反映する。 */
  private Checkpoint update(Checkpoint saved, UnaryOperator<Checkpoint> change) {
    var next = change.apply(saved);
    String encoded = encode(next);
    return transaction.execute(
        tx -> {
          var current = load(saved.id());
          if (current.revision() != saved.revision())
            throw new ConcurrentModificationException("Child checkpoint revision changed");
          long size =
              db.sql(
                      "SELECT COALESCE(SUM(length(CAST(snapshot AS BLOB))),0) FROM subagent_checkpoints")
                  .query(Long.class)
                  .single();
          if (size
                  - encode(current).getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                  + encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
              > maxBytes)
            throw new IllegalArgumentException("Durable child storage capacity reached");
          if (db.sql(
                      "UPDATE subagent_checkpoints SET revision=?,snapshot=? WHERE id=? AND revision=?")
                  .params(next.revision(), encoded, saved.id(), saved.revision())
                  .update()
              != 1) throw new ConcurrentModificationException("Child checkpoint revision changed");
          return next;
        });
  }

  /** 補助エージェントへの委譲で用いる複製を返す。 */
  private Checkpoint copy(
      Checkpoint s,
      String status,
      String run,
      int calls,
      long tokens,
      boolean unknown,
      List<Operation> operations,
      String result,
      String hash,
      long pid,
      String start) {
    return new Checkpoint(
        1,
        s.id(),
        s.revision() + 1,
        s.project(),
        s.root(),
        s.session(),
        s.parentRun(),
        s.agent(),
        s.task(),
        s.context(),
        s.baseline(),
        status,
        run,
        s.maxCalls(),
        s.maxTokens(),
        calls,
        tokens,
        unknown,
        s.modelPending(),
        s.pendingModels(),
        operations,
        result,
        hash,
        clock.instant(),
        pid,
        start,
        s.kind(),
        s.graphId());
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  private Checkpoint pending(Checkpoint s, boolean pending) {
    int count = Math.max(0, s.pendingModels() + (pending ? 1 : -1));
    return new Checkpoint(
        s.version(),
        s.id(),
        s.revision(),
        s.project(),
        s.root(),
        s.session(),
        s.parentRun(),
        s.agent(),
        s.task(),
        s.context(),
        s.baseline(),
        s.status(),
        s.run(),
        s.maxCalls(),
        s.maxTokens(),
        s.consumedCalls(),
        s.consumedTokens(),
        s.usageUnknown(),
        count > 0,
        count,
        s.operations(),
        s.result(),
        s.resultHash(),
        s.updated(),
        s.ownerPid(),
        s.ownerStart(),
        s.kind(),
        s.graphId());
  }

  /** 補助エージェントへの委譲において、残量なしに該当するか判定する。 */
  private static boolean exhausted(Checkpoint s) {
    return s.consumedCalls() >= s.maxCalls()
        || s.maxTokens() > 0 && (s.usageUnknown() || s.consumedTokens() >= s.maxTokens());
  }

  /** 確定できない結果を、判定不能の情報として表す。 */
  private static List<Operation> unknown(List<Operation> operations) {
    return operations.stream()
        .map(
            o ->
                o.status().equals("STARTED")
                    ? new Operation(o.id(), o.tool(), o.argumentsHash(), "UNKNOWN", null)
                    : o)
        .toList();
  }

  /** 補助エージェントへの委譲において、稼働中に該当するか判定する。 */
  private static boolean alive(Checkpoint s) {
    return s.ownerPid() > 0
        && s.ownerStart() != null
        && ProcessHandle.of(s.ownerPid())
            .filter(ProcessHandle::isAlive)
            .flatMap(h -> h.info().startInstant())
            .map(i -> i.toString().equals(s.ownerStart()))
            .orElse(false);
  }

  /** 補助エージェントへの委譲に関する識別情報を処理する。 */
  private static void identity(AgentRunContext owner) {
    if (owner == null
        || owner.projectId() == null
        || owner.conversationId().startsWith("subagent:"))
      throw new IllegalArgumentException("Current human Project owner required");
  }

  /** 補助エージェントへの委譲で用いる作業ルートを返す。 */
  private static String root(AgentRunContext owner) {
    try {
      return owner.projectRoot().toRealPath().toString();
    } catch (java.io.IOException e) {
      throw new IllegalArgumentException("Project root unavailable");
    }
  }

  /** 内容の同一性を照合するためのハッシュ値を求める。 */
  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(Checkpoint state) {
    try {
      String encoded = json.writeValueAsString(state);
      if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 262144)
        throw new IllegalArgumentException("Child checkpoint capacity reached");
      return encoded;
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 受け取った形式の内容を復元し、後続の処理で利用できるようにする。 */
  private Checkpoint decode(String value) {
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (value.length() > 262144)
        throw new IllegalStateException("Child checkpoint capacity reached");
      var state = json.readValue(value, Checkpoint.class);
      if (state.version() != 1
          || state.revision() < 0
          || state.consumedCalls() < 0
          || state.consumedTokens() < 0
          || state.operations().size() > 64
          || state.result() != null && !hash(state.result()).equals(state.resultHash()))
        throw new IllegalStateException("Invalid child checkpoint evidence");
      return state;
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Child checkpoint unavailable", e);
    }
  }
}
