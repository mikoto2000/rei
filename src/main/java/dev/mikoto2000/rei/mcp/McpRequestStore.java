package dev.mikoto2000.rei.mcp;

import dev.mikoto2000.rei.application.run.RunSnapshot;
import dev.mikoto2000.rei.application.run.RunStatus;
import dev.mikoto2000.rei.conversation.ConversationAdmissionStore;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.event.CredentialRedactor;
import dev.mikoto2000.rei.storage.StorageDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP要求の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Owned durable index only; canonical Run state comes from the existing admission store.
 */
public final class McpRequestStore {
  /**
   * MCPによる機能の提供で受け渡す要求を表す。
   *
   * @param hash ハッシュ値
   * @param fingerprint 要求内容の照合値
   * @param project プロジェクト
   * @param session 会話
   * @param run 実行
   * @param created 作成
   * @param expires 有効期限
   * @param result 結果
   * @param resultExpires 結果有効期限
   * @param state 状態
   * @param totalCriteria 合計判断基準一覧
   * @param criteria 判断基準一覧
   * @param admissionError 受付エラー
   * @param updatedAt 更新日時
   */
  public record Request(
      String hash,
      String fingerprint,
      String project,
      String session,
      String run,
      Instant created,
      Instant expires,
      String result,
      Instant resultExpires,
      String state,
      int totalCriteria,
      List<String> criteria,
      String admissionError,
      Instant updatedAt) {}

  /** 根拠。 */
  private McpCompletionEvidence evidence = new McpCompletionEvidence(null, null, null);

  /** 再開用の記録一覧。 */
  private dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints;

  /** MCPによる機能の提供で使用する根拠を設定する。 */
  public void setEvidence(McpCompletionEvidence evidence) {
    this.evidence = evidence;
  }

  /** MCPによる機能の提供で使用する再開用の記録一覧を設定する。 */
  public void setCheckpoints(
      dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints) {
    this.checkpoints = checkpoints;
  }

  /** MCPによる機能の提供で用いる再開用の記録を返す。 */
  public Optional<dev.mikoto2000.rei.checkpoint.PersistentCheckpoint> checkpoint(Request r) {
    if (checkpoints == null) return Optional.empty();
    return checkpoints
        .findByRun(r.project(), r.run())
        .filter(c -> c.sessionId().equals(r.session()));
  }

  /** データベースへのアクセス。 */
  private final StorageDatabase db;

  /** 受付台帳。 */
  private final ConversationAdmissionStore admissions;

  /** やり取り一覧。 */
  private final ConversationTurnStore turns;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 結果保持期間／監査記録保持期間。 */
  private final Duration resultRetention, auditRetention;

  /** 指定された値や利用先を使って、McpRequestStoreを初期化する。 */
  public McpRequestStore(
      java.nio.file.Path root,
      ConversationAdmissionStore admissions,
      ConversationTurnStore turns,
      Clock clock,
      Duration resultRetention,
      Duration auditRetention) {
    db = new StorageDatabase(root);
    this.admissions = admissions;
    this.turns = turns;
    this.clock = clock;
    this.resultRetention = resultRetention;
    this.auditRetention = auditRetention;
  }

  /** MCPによる機能の提供で用いる続きの取得位置キーを返す。 */
  public byte[] cursorKey() {
    return db.read(
        c -> {
          try (var q = c.createStatement();
              var r = q.executeQuery("SELECT value FROM mcp_settings WHERE name='cursor_key'")) {
            if (!r.next()) throw new IllegalStateException("Cursor key missing");
            return HexFormat.of().parseHex(r.getString(1));
          }
        });
  }

  /** MCPによる機能の提供で用いる別要求を返す。 */
  public Optional<Request> byRequest(String hash) {
    return query("request_hash", hash);
  }

  /** MCPによる機能の提供で用いる別実行を返す。 */
  public Optional<Request> byRun(String run) {
    return query("run_id", run);
  }

  /** MCPによる機能の提供で用いる検索条件を返す。 */
  private Optional<Request> query(String column, String value) {
    return db.read(
        c -> {
          try (var q =
              c.prepareStatement(
                  "SELECT request_hash,fingerprint,project_id,session_id,run_id,created_at,expires_at,result_json,result_expires_at,terminal_state,total_criteria,criteria_json,admission_error,state_updated_at FROM mcp_requests WHERE principal='local-mcp' AND "
                      + column
                      + "=?")) {
            q.setString(1, value);
            try (var rows = q.executeQuery()) {
              return rows.next()
                  ? Optional.of(
                      new Request(
                          rows.getString(1),
                          rows.getString(2),
                          rows.getString(3),
                          rows.getString(4),
                          rows.getString(5),
                          Instant.parse(rows.getString(6)),
                          Instant.parse(rows.getString(7)),
                          rows.getString(8),
                          rows.getString(9) == null ? null : Instant.parse(rows.getString(9)),
                          rows.getString(10),
                          rows.getInt(11),
                          Arrays.asList(
                              StorageDatabase.JSON.readValue(rows.getString(12), String[].class)),
                          rows.getString(13),
                          rows.getString(14) == null
                              ? Instant.parse(rows.getString(6))
                              : Instant.parse(rows.getString(14))))
                  : Optional.empty();
            }
          }
        });
  }

  /** MCPによる機能の提供において、owns会話に該当するか判定する。 */
  public boolean ownsSession(String project, String session) {
    return db.read(
        c -> {
          try (var q =
              c.prepareStatement(
                  "SELECT 1 FROM mcp_sessions WHERE session_id=? AND project_id=? AND principal='local-mcp'")) {
            q.setString(1, session);
            q.setString(2, project);
            try (var rows = q.executeQuery()) {
              return rows.next();
            }
          }
        });
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public RunSnapshot status(Request request) {
    return admissions.get(request.run()).orElseThrow(() -> new McpError("RUN_STATE_UNKNOWN"));
  }

  /** MCPによる機能の提供に関する監査記録を処理する。 */
  public void audit(
      String operation,
      String outcome,
      String project,
      String run,
      String hash,
      String correlation) {
    db.transaction(
        c -> {
          try (var q =
              c.prepareStatement("INSERT INTO mcp_audit VALUES(?,'local-mcp',?,?,?,?,?,?,?)")) {
            q.setString(1, UUID.randomUUID().toString());
            q.setString(2, project);
            q.setString(3, run);
            q.setString(4, hash);
            q.setString(5, operation);
            q.setString(6, outcome);
            q.setString(7, clock.instant().toString());
            q.setString(8, correlation);
            q.executeUpdate();
            return null;
          }
        });
  }

  /**
   * 現在の状態を、後から参照できる記録として取り出す。
   *
   * <p>Snapshot only persisted final assistant output; tool logs and reasoning are never projected.
   */
  public void snapshot(Request request) {
    if (request.state() != null) return;
    var run = status(request);
    // 終了済みの実行について、現在の状態に応じた扱いを選ぶ。
    if (!run.status().isTerminal()) return;
    var turn = turns.findRun(request.session(), request.run());
    if (turn.isPresent() && turn.get().status() == ConversationTurnStore.Status.RUNNING) return;
    if (turn.isEmpty() && run.status() == RunStatus.COMPLETED) return;
    String content =
        CredentialRedactor.redact(
            turn.map(ConversationTurnStore.Turn::assistantMessage).orElse(""));
    var result = new LinkedHashMap<String, Object>();
    result.put("content", content);
    result.put("artifacts", evidence.artifacts(request));
    result.put("completion", evidence.completion(request, run.status() == RunStatus.COMPLETED));
    result.put(
        "unresolved_items",
        ((Number) ((Map<?, ?>) result.get("completion")).get("verified_criteria")).intValue()
                < request.totalCriteria()
            ? List.of("Acceptance criteria have no saved verification evidence")
            : List.of());
    try {
      String json = StorageDatabase.JSON.writeValueAsString(result);
      db.transaction(
          c -> {
            try (var q =
                c.prepareStatement(
                    "UPDATE mcp_requests SET result_json=?,result_expires_at=?,terminal_state=? WHERE run_id=? AND terminal_state IS NULL")) {
              q.setString(1, json);
              q.setString(
                  2,
                  (run.completedAt() == null ? clock.instant() : run.completedAt())
                      .plus(resultRetention)
                      .toString());
              q.setString(3, run.status().name());
              q.setString(4, request.run());
              if (q.executeUpdate() == 1) auditInTransaction(c, request, run.status().name());
              return null;
            }
          });
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot save MCP result", error);
    }
  }

  /** MCPによる機能の提供に関する監査記録入力先一連の更新を処理する。 */
  private void auditInTransaction(java.sql.Connection c, Request request, String state)
      throws java.sql.SQLException {
    try (var q = c.prepareStatement("INSERT INTO mcp_audit VALUES(?,'local-mcp',?,?,?,?,?,?,?)")) {
      q.setString(1, UUID.randomUUID().toString());
      q.setString(2, request.project());
      q.setString(3, request.run());
      q.setString(4, request.hash());
      q.setString(5, "run_terminal");
      q.setString(6, state);
      q.setString(7, clock.instant().toString());
      q.setString(8, UUID.randomUUID().toString());
      q.executeUpdate();
    }
  }

  /** 後から状況を確認できるよう、完了を記録する。 */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 1000)
  public void captureCompleted() {
    var ids =
        db.read(
            c -> {
              var result = new ArrayList<String>();
              try (var q = c.createStatement();
                  var rows =
                      q.executeQuery(
                          "SELECT run_id FROM mcp_requests WHERE terminal_state IS NULL LIMIT 100")) {
                while (rows.next()) result.add(rows.getString(1));
              }
              return result;
            });
    for (String id : ids) byRun(id).ifPresent(this::snapshot);
  }

  /** MCPによる機能の提供に関するexpireを処理する。 */
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 3600000)
  public void expire() {
    var expired =
        db.read(
            c -> {
              var ids = new ArrayList<String>();
              try (var q =
                  c.prepareStatement(
                      "SELECT run_id FROM mcp_requests WHERE result_json IS NOT NULL AND result_expires_at<=?")) {
                q.setString(1, clock.instant().toString());
                try (var r = q.executeQuery()) {
                  while (r.next()) ids.add(r.getString(1));
                }
              }
              return ids;
            });
    for (var id : expired)
      byRun(id)
          .ifPresent(
              r ->
                  audit(
                      "result_expired",
                      "RESULT_EXPIRED",
                      r.project(),
                      r.run(),
                      r.hash(),
                      UUID.randomUUID().toString()));
    db.transaction(
        c -> {
          try (var q =
              c.prepareStatement(
                  "UPDATE mcp_requests SET result_json=NULL WHERE result_expires_at<=?")) {
            q.setString(1, clock.instant().toString());
            q.executeUpdate();
          }
          try (var q =
              c.prepareStatement(
                  "UPDATE mcp_requests SET criteria_json='[]' WHERE expires_at<=? AND terminal_state IS NOT NULL AND result_json IS NULL")) {
            q.setString(1, clock.instant().toString());
            q.executeUpdate();
          }
          try (var q = c.prepareStatement("DELETE FROM mcp_audit WHERE created_at<?")) {
            q.setString(1, clock.instant().minus(auditRetention).toString());
            q.executeUpdate();
          }
          return null;
        });
  }
}
