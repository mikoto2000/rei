package dev.mikoto2000.rei.core.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.tooldelivery.ToolResultMetadata;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 承認継続入力の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>A durable receipt is claimed before dispatch; an interrupted claim is never replayed.
 */
@Repository
public class ApprovalContinuationStore {
  /**
   * ツール利用の権限制御で受け渡す保存済みメッセージを表す。
   *
   * @param kind 種類
   * @param text テキスト
   * @param calls 呼び出し回数
   * @param responses 応答一覧
   * @param delivery 登録済みツール結果の配信情報（旧形式では未指定）
   */
  public record StoredMessage(
      String kind,
      String text,
      List<AssistantMessage.ToolCall> calls,
      List<ToolResponseMessage.ToolResponse> responses,
      ToolResultMetadata delivery) {
    /** 保存後に外部から呼び出しや応答の一覧を変更できないようにする。 */
    public StoredMessage {
      calls = calls == null ? List.of() : List.copyOf(calls);
      responses = responses == null ? List.of() : List.copyOf(responses);
    }

    /** 配信属性を持たないメッセージと旧形式の呼び出しを受け付ける。 */
    public StoredMessage(
        String kind,
        String text,
        List<AssistantMessage.ToolCall> calls,
        List<ToolResponseMessage.ToolResponse> responses) {
      this(kind, text, calls, responses, null);
    }
  }

  /**
   * ツール利用の権限制御で受け渡す文書を表す。
   *
   * @param owner 所有者
   * @param request 要求
   * @param history 履歴
   * @param pending 待機中
   * @param budget 予算
   * @param managedParent 管理対象親
   */
  public record Document(
      AgentRunContext owner,
      String request,
      List<StoredMessage> history,
      RunContinuation.PendingTools pending,
      OutputLimitRunBudget.Snapshot budget,
      boolean managedParent) {}

  /**
   * ツール利用の権限制御で受け渡す項目を表す。
   *
   * @param approvalId 承認識別子
   * @param runId 実行の識別子
   * @param state 状態
   * @param document 文書
   * @param pid プロセス識別子
   * @param started 開始
   */
  public record Entry(
      String approvalId, String runId, String state, String document, long pid, String started) {}

  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 指定された値や利用先を使って、ApprovalContinuationStoreを初期化する。 */
  public ApprovalContinuationStore(@Qualifier("memoryConsolidationDataSource") DataSource source) {
    db = JdbcClient.create(source);
    db.sql(
            "CREATE TABLE IF NOT EXISTS approval_continuations(approval TEXT PRIMARY KEY,run TEXT NOT NULL,state TEXT NOT NULL,document TEXT NOT NULL,pid INTEGER NOT NULL DEFAULT 0,started TEXT)")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS approval_continuations_run ON approval_continuations(run,state)")
        .update();
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public void save(
      String id,
      AgentRunContext owner,
      String request,
      List<Message> history,
      RunContinuation.PendingTools pending,
      OutputLimitRunBudget.Snapshot budget,
      boolean managed) {
    if (pending == null
        || pending.calls().isEmpty()
        || pending.completed().size() >= pending.calls().size())
      throw new IllegalArgumentException("Pending Tool required");
    var messages =
        history.stream()
            .filter(m -> !(m instanceof SystemMessage))
            .map(
                m -> {
                  if (m instanceof AssistantMessage a)
                    return new StoredMessage(
                        "ASSISTANT",
                        Objects.toString(a.getText(), ""),
                        a.getToolCalls(),
                        List.of());
                  if (m instanceof ToolResponseMessage t)
                    return new StoredMessage(
                        "TOOL", "", List.of(), t.getResponses(), ToolResultMetadata.capture(t));
                  if (m instanceof UserMessage)
                    return new StoredMessage("USER", m.getText(), List.of(), List.of());
                  throw new IllegalArgumentException("Unsupported continuation message");
                })
            .toList();
    String encoded = encode(new Document(owner, request, messages, pending, budget, managed));
    if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576
        || messages.size() > 256
        || pending.calls().size() > 32)
      throw new IllegalArgumentException("Approval continuation capacity exceeded");
    if (db.sql(
                "INSERT OR IGNORE INTO approval_continuations(approval,run,state,document) VALUES(?,?,'WAITING_APPROVAL',?)")
            .params(id, owner.runId(), encoded)
            .update()
        != 1) throw new IllegalStateException("Approval already has a continuation");
  }

  /** 行。 */
  private static final org.springframework.jdbc.core.RowMapper<Entry> ROW =
      (rs, n) ->
          new Entry(
              rs.getString("approval"),
              rs.getString("run"),
              rs.getString("state"),
              rs.getString("document"),
              rs.getLong("pid"),
              rs.getString("started"));

  /** 対象を特定できるよう、承認継続入力保存先を探す。 */
  public Optional<Entry> find(String id) {
    return db.sql("SELECT * FROM approval_continuations WHERE approval=?")
        .param(id)
        .query(ROW)
        .optional();
  }

  /** ツール利用の権限制御で用いるresumingを返す。 */
  public Optional<Entry> resuming(String run) {
    return db.sql("SELECT * FROM approval_continuations WHERE run=? AND state='RESUMING'")
        .param(run)
        .query(ROW)
        .optional();
  }

  /** ツール利用の権限制御で用いる表示対象を返す。 */
  public List<String> visible(String project) {
    return db.sql(
            "SELECT approval FROM approval_continuations WHERE json_extract(document,'$.owner.projectId')=? AND state IN ('WAITING_APPROVAL','QUEUED','RESUMING','UNKNOWN','EXPIRED','DENY_QUEUED') ORDER BY rowid DESC LIMIT 256")
        .param(project)
        .query(String.class)
        .list();
  }

  /** ツール利用の権限制御で用いる活動中を返す。 */
  public List<Entry> active() {
    return db.sql(
            "SELECT * FROM approval_continuations WHERE state IN ('WAITING_APPROVAL','QUEUED','RESUMING','DENY_QUEUED') ORDER BY approval LIMIT 256")
        .query(ROW)
        .list();
  }

  /** ツール利用の権限制御に関する拒否を処理する。 */
  public void deny(String id) {
    db.sql(
            "UPDATE approval_continuations SET state='DENY_QUEUED' WHERE approval=? AND state='WAITING_APPROVAL'")
        .param(id)
        .update();
  }

  /** ツール利用の権限制御で用いる文書を返す。 */
  public Document document(Entry entry) {
    try {
      return json.readValue(entry.document(), Document.class);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Invalid approval continuation", error);
    }
  }

  /** ツール利用の権限制御で用いる保存済みを返す。 */
  public RunContinuation.Saved saved(Entry entry) {
    var d = document(entry);
    return new RunContinuation.Saved(
        d.request(),
        d.history().stream()
            .map(
                m ->
                    (Message)
                        switch (m.kind()) {
                          case "USER" -> new UserMessage(m.text());
                          case "ASSISTANT" ->
                              AssistantMessage.builder()
                                  .content(m.text())
                                  .toolCalls(m.calls())
                                  .build();
                          case "TOOL" ->
                              ToolResponseMessage.builder()
                                  .responses(m.responses())
                                  .metadata(
                                      m.delivery() == null
                                          ? java.util.Map.of()
                                          : m.delivery().restore())
                                  .build();
                          default ->
                              throw new IllegalStateException("Invalid continuation message");
                        })
            .toList(),
        d.budget(),
        d.pending());
  }

  /** ツール利用の権限制御において、実行待ちに該当するか判定する。 */
  public boolean queue(String id) {
    return db.sql(
                "UPDATE approval_continuations SET state='QUEUED' WHERE approval=? AND state='WAITING_APPROVAL'")
            .param(id)
            .update()
        == 1;
  }

  /** 対象を処理する担当を決めるため、承認継続入力保存先の利用権を確保する。 */
  public boolean claim(String id) {
    return db.sql(
                "UPDATE approval_continuations SET state='RESUMING',pid=?,started=? WHERE approval=? AND state='QUEUED'")
            .params(
                ProcessHandle.current().pid(),
                ProcessHandle.current().info().startInstant().orElseThrow().toString(),
                id)
            .update()
        == 1;
  }

  /** ツール利用の権限制御に関する状態を処理する。 */
  public void state(String id, String state) {
    if (!Set.of("COMPLETED", "CANCELLED", "DENIED", "EXPIRED", "UNKNOWN").contains(state))
      throw new IllegalArgumentException("Invalid continuation terminal state");
    db.sql(
            "UPDATE approval_continuations SET state=? WHERE approval=? AND state IN ('WAITING_APPROVAL','QUEUED','RESUMING','DENY_QUEUED')")
        .params(state, id)
        .update();
  }

  /** 終了時の状態を確定するため、実行を完了させる。 */
  public void finishRun(String run, String state) {
    for (var entry : active()) if (entry.runId().equals(run)) state(entry.approvalId(), state);
  }

  /** ツール利用の権限制御に関する案内を処理する。 */
  public void guidance(AgentRunContext owner, List<Message> guidance) {
    for (var entry : active())
      if (entry.runId().equals(owner.runId()) && !entry.state().equals("RESUMING")) {
        var d = document(entry);
        var messages = new ArrayList<>(d.history());
        for (var message : guidance) {
          if (!(message instanceof UserMessage))
            throw new IllegalArgumentException("Human guidance required");
          messages.add(new StoredMessage("USER", message.getText(), List.of(), List.of()));
        }
        String encoded =
            encode(
                new Document(
                    d.owner(), d.request(), messages, d.pending(), d.budget(), d.managedParent()));
        // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
        if (encoded.length() > 1048576 || messages.size() > 256)
          throw new IllegalArgumentException("Approval guidance capacity exceeded");
        db.sql("UPDATE approval_continuations SET document=? WHERE approval=? AND state=?")
            .params(encoded, entry.approvalId(), entry.state())
            .update();
      }
  }

  /** ツール利用の権限制御において、所有者稼働中に該当するか判定する。 */
  public boolean ownerAlive(Entry entry) {
    return entry.pid() > 0
        && entry.started() != null
        && ProcessHandle.of(entry.pid())
            .filter(ProcessHandle::isAlive)
            .flatMap(p -> p.info().startInstant())
            .map(t -> t.toString().equals(entry.started()))
            .orElse(false);
  }

  /** 受け渡し先が扱える形式へ、指定された内容を変換する。 */
  private String encode(Document d) {
    try {
      return json.writeValueAsString(d);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot save approval continuation", error);
    }
  }
}
