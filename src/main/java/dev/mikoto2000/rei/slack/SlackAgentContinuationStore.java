package dev.mikoto2000.rei.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Slackエージェント継続入力の記録を保持し、後続の処理で参照できるようにする。
 *
 * <p>Persist only the non-system conversation and exact ToolCall/ToolResponse pairs.
 */
public final class SlackAgentContinuationStore {
  /** データベースへのアクセス。 */
  private final JdbcClient db;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper();

  /**
   * Slackとの連携で受け渡す保存済みメッセージを表す。
   *
   * @param kind 種類
   * @param text テキスト
   * @param calls 呼び出し回数
   * @param responses 応答一覧
   */
  public record StoredMessage(
      String kind,
      String text,
      List<AssistantMessage.ToolCall> calls,
      List<ToolResponseMessage.ToolResponse> responses) {}

  /**
   * Slackとの連携で受け渡す文書を表す。
   *
   * @param run 実行
   * @param project プロジェクト
   * @param root 作業ルート
   * @param session 会話
   * @param request 要求
   * @param messages メッセージ一覧
   * @param budget 予算
   * @param managedParent 管理対象親
   */
  public record Document(
      String run,
      String project,
      String root,
      String session,
      String request,
      List<StoredMessage> messages,
      OutputLimitRunBudget.Snapshot budget,
      boolean managedParent) {}

  /** 指定された値や利用先を使って、SlackAgentContinuationStoreを初期化する。 */
  public SlackAgentContinuationStore(DataSource source) {
    db = JdbcClient.create(source);
    db.sql(
            "CREATE TABLE IF NOT EXISTS slack_agent_continuations(run TEXT PRIMARY KEY,document TEXT NOT NULL,state TEXT NOT NULL)")
        .update();
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public void save(
      AgentRunContext o,
      String request,
      List<Message> history,
      OutputLimitRunBudget.Snapshot budget) {
    save(o, request, history, budget, false);
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public void save(
      AgentRunContext o,
      String request,
      List<Message> history,
      OutputLimitRunBudget.Snapshot budget,
      boolean managedParent) {
    var messages =
        history.stream()
            .filter(m -> !(m instanceof SystemMessage))
            .map(
                m -> {
                  if (m instanceof AssistantMessage a)
                    return new StoredMessage(
                        "ASSISTANT",
                        SlackReplies.safe(Objects.toString(a.getText(), "")),
                        a.getToolCalls(),
                        List.of());
                  if (m instanceof ToolResponseMessage t)
                    return new StoredMessage(
                        "TOOL",
                        "",
                        List.of(),
                        t.getResponses().stream()
                            .map(
                                r ->
                                    new ToolResponseMessage.ToolResponse(
                                        r.id(), r.name(), SlackReplies.safe(r.responseData())))
                            .toList());
                  if (m instanceof UserMessage)
                    return new StoredMessage(
                        "USER", SlackReplies.safe(m.getText()), List.of(), List.of());
                  throw new IllegalArgumentException("Unsupported continuation message");
                })
            .toList();
    try {
      String document =
          json.writeValueAsString(
              new Document(
                  o.runId(),
                  o.projectId(),
                  o.projectRoot().toString(),
                  o.conversationId(),
                  SlackReplies.safe(request),
                  messages,
                  budget,
                  managedParent));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (document.length() > 524288 || messages.size() > 256)
        throw new IllegalArgumentException("Continuation capacity exceeded");
      if (db.sql(
                  "INSERT INTO slack_agent_continuations VALUES(?,?,'WAITING') ON CONFLICT(run) DO UPDATE SET document=excluded.document,state='WAITING' WHERE state='RESUMING'")
              .params(o.runId(), document)
              .update()
          != 1) throw new IllegalStateException("Continuation already saved or stopped");
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("Cannot save continuation");
    }
  }

  /** Slackとの連携で用いる文書を返す。 */
  public Document document(String run) {
    try {
      return json.readValue(
          db.sql("SELECT document FROM slack_agent_continuations WHERE run=?")
              .param(run)
              .query(String.class)
              .single(),
          Document.class);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Invalid saved continuation");
    }
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public RunContinuation.Saved get(AgentRunContext o) {
    var d = document(o.runId());
    if (!Objects.equals(d.project(), o.projectId())
        || !d.root().equals(o.projectRoot().toString())
        || !d.session().equals(o.conversationId()))
      throw new IllegalArgumentException("Continuation ownership mismatch");
    var messages =
        d.messages().stream()
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
                              ToolResponseMessage.builder().responses(m.responses()).build();
                          default -> throw new IllegalStateException("Invalid saved message kind");
                        })
            .toList();
    return new RunContinuation.Saved(d.request(), messages, d.budget());
  }

  /** Slackとの連携で用いる状態を返す。 */
  public String state(String run) {
    return db.sql("SELECT state FROM slack_agent_continuations WHERE run=?")
        .param(run)
        .query(String.class)
        .optional()
        .orElse(null);
  }

  /** 既存の記録へ経緯を残すため、案内を追記する。 */
  public void appendGuidance(AgentRunContext owner, List<Message> guidance) {
    get(owner);
    var d = document(owner.runId());
    var messages = new ArrayList<>(d.messages());
    for (var m : guidance) {
      if (!(m instanceof UserMessage))
        throw new IllegalArgumentException("Only accepted human guidance can extend a wait");
      messages.add(new StoredMessage("USER", SlackReplies.safe(m.getText()), List.of(), List.of()));
    }
    try {
      String value =
          json.writeValueAsString(
              new Document(
                  d.run(),
                  d.project(),
                  d.root(),
                  d.session(),
                  d.request(),
                  messages,
                  d.budget(),
                  d.managedParent()));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (value.length() > 524288 || messages.size() > 256)
        throw new IllegalArgumentException("Continuation capacity exceeded");
      if (db.sql("UPDATE slack_agent_continuations SET document=? WHERE run=? AND state='WAITING'")
                  .params(value, owner.runId())
                  .update()
              != 1
          && !"CANCELLED".equals(state(owner.runId())))
        throw new IllegalStateException("Waiting guidance ownership lost");
    } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
      throw new IllegalStateException("Cannot save guidance");
    }
  }

  /** 対象を処理する担当を決めるため、Slackエージェント継続入力保存先の利用権を確保する。 */
  public boolean claim(String run) {
    return db.sql(
                "UPDATE slack_agent_continuations SET state='RESUMING' WHERE run=? AND state='WAITING'")
            .param(run)
            .update()
        == 1;
  }

  /** Slackとの連携で用いる待機中を返す。 */
  public List<String> waiting() {
    return db.sql(
            "SELECT run FROM slack_agent_continuations WHERE state='WAITING' ORDER BY run LIMIT 256")
        .query(String.class)
        .list();
  }

  /** 対象の実行をキャンセルする。 */
  public void cancel(String run) {
    db.sql(
            "UPDATE slack_agent_continuations SET state='CANCELLED' WHERE run=? AND state IN ('WAITING','RESUMING')")
        .param(run)
        .update();
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  public void complete(String run) {
    db.sql(
            "UPDATE slack_agent_continuations SET state='COMPLETED' WHERE run=? AND state='RESUMING'")
        .param(run)
        .update();
  }

  /** 確定できない結果を、判定不能の情報として表す。 */
  public void unknown(String run) {
    db.sql(
            "UPDATE slack_agent_continuations SET state='UNKNOWN' WHERE run=? AND state IN ('WAITING','RESUMING')")
        .param(run)
        .update();
  }

  /** 処理を継続できる状態に戻すため、Slackエージェント継続入力保存先を復旧する。 */
  public void recover() {
    db.sql("UPDATE slack_agent_continuations SET state='UNKNOWN' WHERE state='RESUMING'").update();
  }
}
