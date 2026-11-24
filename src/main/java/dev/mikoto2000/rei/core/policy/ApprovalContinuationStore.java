package dev.mikoto2000.rei.core.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import dev.mikoto2000.rei.tooldelivery.ToolResultMetadata;
import java.util.ArrayList;
import java.util.Collections;
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
   * @param payload 完全なモデルメッセージへの検証済み参照（旧形式では未指定）
   */
  public record StoredMessage(
      String kind,
      String text,
      List<AssistantMessage.ToolCall> calls,
      List<ToolResponseMessage.ToolResponse> responses,
      ToolResultMetadata delivery,
      ModelMessagePayloadStore.Ref payload,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          Long historySequence) {
    /** 保存後に外部から呼び出しや応答の一覧を変更できないようにする。 */
    public StoredMessage {
      calls = calls == null ? List.of() : List.copyOf(calls);
      responses = responses == null ? List.of() : List.copyOf(responses);
      if (historySequence != null && historySequence < 0)
        throw new IllegalArgumentException("Invalid history projection");
    }

    public StoredMessage(
        String kind,
        String text,
        List<AssistantMessage.ToolCall> calls,
        List<ToolResponseMessage.ToolResponse> responses,
        ToolResultMetadata delivery,
        ModelMessagePayloadStore.Ref payload) {
      this(kind, text, calls, responses, delivery, payload, null);
    }

    public StoredMessage(
        String kind,
        String text,
        List<AssistantMessage.ToolCall> calls,
        List<ToolResponseMessage.ToolResponse> responses,
        ToolResultMetadata delivery) {
      this(kind, text, calls, responses, delivery, null);
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
      boolean managedParent,
      @com.fasterxml.jackson.annotation.JsonInclude(
              com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
          ModelMessagePayloadStore.History protocolHistory) {
    public Document {
      history = history == null ? null : Collections.unmodifiableList(new ArrayList<>(history));
    }

    public Document(
        AgentRunContext owner,
        String request,
        List<StoredMessage> history,
        RunContinuation.PendingTools pending,
        OutputLimitRunBudget.Snapshot budget,
        boolean managedParent) {
      this(owner, request, history, pending, budget, managedParent, null);
    }
  }

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

  private final org.springframework.util.function.SingletonSupplier<ModelMessagePayloadStore>
      payloads;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  /** 指定された値や利用先を使って、ApprovalContinuationStoreを初期化する。 */
  public ApprovalContinuationStore(@Qualifier("memoryConsolidationDataSource") DataSource source) {
    db = JdbcClient.create(source);
    payloads =
        org.springframework.util.function.SingletonSupplier.of(
            () -> new ModelMessagePayloadStore(source));
    db.sql(
            "CREATE TABLE IF NOT EXISTS approval_continuations(approval TEXT PRIMARY KEY,run TEXT NOT NULL,state TEXT NOT NULL,document TEXT NOT NULL,pid INTEGER NOT NULL DEFAULT 0,started TEXT)")
        .update();
    db.sql(
            "CREATE INDEX IF NOT EXISTS approval_continuations_run ON approval_continuations(run,state)")
        .update();
  }

  public void save(
      String id,
      AgentRunContext owner,
      String request,
      List<Message> history,
      RunContinuation.PendingTools pending,
      OutputLimitRunBudget.Snapshot budget,
      boolean managed) {
    save(id, owner, request, history, pending, budget, managed, null);
  }

  /** 後から参照できるよう、指定された情報を保存する。 */
  public void save(
      String id,
      AgentRunContext owner,
      String request,
      List<Message> history,
      RunContinuation.PendingTools pending,
      OutputLimitRunBudget.Snapshot budget,
      boolean managed,
      ModelMessagePayloadStore.History protocolHistory) {
    if (pending == null
        || pending.calls().isEmpty()
        || pending.completed().size() >= pending.calls().size())
      throw new IllegalArgumentException("Pending Tool required");
    if (pending.calls().size() > 32
        || history.stream()
                .filter(message -> !(message instanceof SystemMessage))
                .limit(257)
                .count()
            > 256) throw new IllegalArgumentException("Approval continuation capacity exceeded");
    if (pending.assistantReference() != null) {
      var scope = ModelMessagePayloadStore.Scope.forOwner(owner);
      payloads
          .obtain()
          .publish(
              scope,
              pending.assistantReference(),
              ModelMessagePayloadStore.ReferenceKind.APPROVAL,
              id);
    }
    if (pending.completedReference() != null) {
      payloads
          .obtain()
          .publish(
              ModelMessagePayloadStore.Scope.forOwner(owner),
              pending.completedReference(),
              ModelMessagePayloadStore.ReferenceKind.APPROVAL,
              id);
    }
    if (protocolHistory != null) {
      var scope = ModelMessagePayloadStore.Scope.forOwner(owner);
      if (!owner.runId().equals(protocolHistory.run())
          || !new ModelMessagePayloadStore.Exchange(
                  payloads.obtain(), scope, owner.runId(), "approval")
              .ownerKey()
              .equals(protocolHistory.origin().owner())
          || pending.assistantReference() == null)
        throw new IllegalStateException("Native continuation ownership mismatch");
      protocolHistory
          .origin()
          .requireMatch(
              payloads
                  .obtain()
                  .load(
                      scope,
                      pending.assistantReference(),
                      ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                      id));
      payloads.obtain().protectContinuation(scope, protocolHistory, id);
    }
    var messageIndex = new java.util.concurrent.atomic.AtomicInteger();
    var messages =
        history.stream()
            .filter(m -> !(m instanceof SystemMessage))
            .map(m -> {
              int index = messageIndex.getAndIncrement();
              if (dev.mikoto2000.rei.modelmessage.ModelMessageCodec.requiresPayload(m)) {
                Object position = m.getMetadata().get("rei.historySequence");
                Long sequence = null;
                if (position instanceof Long value) sequence = value;
                else if (position instanceof Integer value) sequence = value.longValue();
                else if (position != null)
                  throw new IllegalStateException("Invalid history projection");
                if (sequence != null && sequence < 0)
                  throw new IllegalStateException("Invalid history projection");
                var scope = ModelMessagePayloadStore.Scope.forOwner(owner);
                var ref =
                    payloads
                        .obtain()
                        .saveForContinuation(
                            scope, owner.runId(), "approval:" + id + "/history:" + index, m);
                payloads
                    .obtain()
                    .publish(scope, ref, ModelMessagePayloadStore.ReferenceKind.APPROVAL, id);
                return new StoredMessage(
                    "MODEL_" + m.getMessageType().name(),
                    "",
                    List.of(),
                    List.of(),
                    null,
                    ref,
                    sequence);
              }
              if (m instanceof AssistantMessage a)
                return new StoredMessage(
                    "ASSISTANT", Objects.toString(a.getText(), ""), a.getToolCalls(), List.of());
              if (m instanceof ToolResponseMessage t)
                return new StoredMessage(
                    "TOOL", "", List.of(), t.getResponses(), ToolResultMetadata.capture(t));
              if (m instanceof UserMessage)
                return new StoredMessage("USER", m.getText(), List.of(), List.of());
              throw new IllegalArgumentException("Unsupported continuation message");
            })
            .toList();
    var storedPending =
        pending.completedReference() == null
            ? pending
            : new RunContinuation.PendingTools(
                pending.text(),
                pending.calls(),
                List.of(),
                pending.argumentCorrections(),
                pending.schemaVersion(),
                pending.assistantReference(),
                pending.completedCount(),
                pending.completedReference());
    String encoded =
        encode(
            new Document(
                owner, request, messages, storedPending, budget, managed, protocolHistory));
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
      throw new IllegalStateException("Invalid approval continuation");
    }
  }

  /** ツール利用の権限制御で用いる保存済みを返す。 */
  public RunContinuation.Saved saved(Entry entry) {
    var d = document(entry);
    var pending = d.pending();
    Message pendingMessage = null;
    if (d.pending() != null && d.pending().assistantReference() != null)
      pendingMessage =
          payloads
              .obtain()
              .load(
                  ModelMessagePayloadStore.Scope.forOwner(d.owner()),
                  d.pending().assistantReference(),
                  ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                  entry.approvalId());
    if (pending != null && pending.completedReference() != null) {
      var saved =
          payloads
              .obtain()
              .load(
                  ModelMessagePayloadStore.Scope.forOwner(d.owner()),
                  pending.completedReference(),
                  ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                  entry.approvalId());
      if (!(saved instanceof ToolResponseMessage tool)
          || tool.getResponses().size() != pending.completedCount())
        throw new IllegalStateException("Invalid completed model message reference");
      pending =
          new RunContinuation.PendingTools(
              pending.text(),
              pending.calls(),
              tool.getResponses(),
              pending.argumentCorrections(),
              pending.assistantReference(),
              pending.completedReference());
    }
    var messages =
        d.history().stream()
            .map(m -> {
              if (m.payload() != null) {
                var message =
                    payloads
                        .obtain()
                        .load(
                            ModelMessagePayloadStore.Scope.forOwner(d.owner()),
                            m.payload(),
                            ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                            entry.approvalId());
                if (!("MODEL_" + message.getMessageType().name()).equals(m.kind()))
                  throw new IllegalStateException("Model message persistence rejected");
                if (m.historySequence() == null) return message;
                var metadata = new java.util.HashMap<>(message.getMetadata());
                metadata.put("rei.historySequence", m.historySequence());
                if (message instanceof AssistantMessage assistant)
                  return (Message)
                      AssistantMessage.builder()
                          .parts(assistant.getParts())
                          .properties(metadata)
                          .build();
                if (message instanceof UserMessage user)
                  return (Message)
                      UserMessage.builder()
                          .text(user.getText())
                          .media(user.getMedia())
                          .metadata(metadata)
                          .build();
                throw new IllegalStateException("Invalid history projection");
              }
              return (Message)
                  switch (m.kind()) {
                    case "USER" -> new UserMessage(m.text());
                    case "ASSISTANT" ->
                        AssistantMessage.builder().content(m.text()).toolCalls(m.calls()).build();
                    case "TOOL" ->
                        ToolResponseMessage.builder()
                            .responses(m.responses())
                            .metadata(
                                m.delivery() == null ? java.util.Map.of() : m.delivery().restore())
                            .build();
                    default -> throw new IllegalStateException("Invalid continuation message");
                  };
            })
            .toList();
    if (d.protocolHistory() != null && d.protocolHistory().input() != null) {
      var scope = ModelMessagePayloadStore.Scope.forOwner(d.owner());
      messages =
          d.protocolHistory().input().entries().stream()
              .map(pointer ->
                  payloads
                      .obtain()
                      .loadEntry(
                          scope,
                          pointer,
                          ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                          entry.approvalId()))
              .toList();
    }
    List<ModelMessagePayloadStore.ReplayReference> references = null;
    if (d.protocolHistory() != null
        || backendNative(pendingMessage)
        || messages.stream().anyMatch(ApprovalContinuationStore::backendNative)) {
      var nativeReferences = new ArrayList<ModelMessagePayloadStore.ReplayReference>();
      for (var message : d.history())
        if (message.payload() != null)
          nativeReferences.add(
              new ModelMessagePayloadStore.ReplayReference(
                  message.payload(),
                  ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                  entry.approvalId()));
      if (pending != null && pending.assistantReference() != null)
        nativeReferences.add(
            new ModelMessagePayloadStore.ReplayReference(
                pending.assistantReference(),
                ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                entry.approvalId()));
      if (pending != null && pending.completedReference() != null)
        nativeReferences.add(
            new ModelMessagePayloadStore.ReplayReference(
                pending.completedReference(),
                ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                entry.approvalId()));
      if (d.protocolHistory() != null)
        for (var trace : d.protocolHistory().protectedEntries())
          nativeReferences.add(
              new ModelMessagePayloadStore.ReplayReference(
                  trace.reference(),
                  ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                  entry.approvalId()));
      references = nativeReferences.stream().distinct().toList();
    }
    return new RunContinuation.Saved(
        d.request(), messages, d.budget(), pending, references, d.protocolHistory());
  }

  private static boolean backendNative(Message message) {
    return message instanceof AssistantMessage
        && message.getMetadata().get("rei.llm.replay-origin-owner") instanceof String owner
        && owner.matches("[0-9a-fA-F]{64}");
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
    if (state.equals("COMPLETED") || state.equals("DENIED"))
      retirePayloadReferences(id, state);
  }

  private void retirePayloadReferences(String id, String state) {
    try {
      var entry = find(id).filter(found -> found.state().equals(state)).orElse(null);
      if (entry == null) return;
      var document = document(entry);
      var pending = document.pending();
      var history = document.history();
      boolean hasPayload = document.protocolHistory() != null
          || pending != null && (pending.assistantReference() != null || pending.completedReference() != null)
          || history != null && history.stream().anyMatch(message -> message.payload() != null);
      if (!hasPayload) return;
      var scope = ModelMessagePayloadStore.Scope.forOwner(document.owner());
      Runnable retire = () -> {
        try {
          payloads.obtain().releaseApproval(scope, entry.approvalId());
        } catch (RuntimeException retained) {
          org.slf4j.LoggerFactory.getLogger(ApprovalContinuationStore.class)
              .warn("Terminal approval model message references retained after cleanup failure");
        }
      };
      if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) return;
        org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              @Override public void afterCommit() { retire.run(); }
            });
      } else retire.run();
    } catch (RuntimeException retained) {
      org.slf4j.LoggerFactory.getLogger(ApprovalContinuationStore.class)
          .warn("Terminal approval model message references retained after cleanup failure");
    }
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
        var history = d.protocolHistory();
        if (history != null && history.input() != null) {
          if (history.entries().size() + guidance.size() > 256
              || history.input().entries().size() + guidance.size() > 256)
            throw new IllegalArgumentException("Approval guidance capacity exceeded");
          if (guidance.stream().anyMatch(message -> !(message instanceof UserMessage)))
            throw new IllegalArgumentException("Human guidance required");
          var ownEntries = new ArrayList<>(history.entries());
          var inputEntries = new ArrayList<>(history.input().entries());
          var scope = ModelMessagePayloadStore.Scope.forOwner(d.owner());
          var store = payloads.obtain();
          for (var message : guidance) {
            var key = "guidance/" + java.util.UUID.randomUUID() + "/USER";
            var reference = store.save(scope, d.owner().runId(), key, message);
            store.publish(
                scope, reference, ModelMessagePayloadStore.ReferenceKind.RUN, d.owner().runId());
            store.publish(
                scope,
                reference,
                ModelMessagePayloadStore.ReferenceKind.APPROVAL,
                entry.approvalId());
            var pointer = new ModelMessagePayloadStore.History.Entry(key, reference);
            ownEntries.add(pointer);
            inputEntries.add(pointer);
          }
          history =
              new ModelMessagePayloadStore.History(
                  history.schemaVersion(),
                  history.run(),
                  history.origin(),
                  ownEntries,
                  new ModelMessagePayloadStore.History.Input(
                      inputEntries,
                      history.input().traceSize(),
                      history.input().preparationFingerprint()));
        }
        for (var message : guidance) {
          if (!(message instanceof UserMessage))
            throw new IllegalArgumentException("Human guidance required");
          messages.add(new StoredMessage("USER", message.getText(), List.of(), List.of()));
        }
        String encoded =
            encode(
                new Document(
                    d.owner(),
                    d.request(),
                    messages,
                    d.pending(),
                    d.budget(),
                    d.managedParent(),
                    history));
        // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
        if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576
            || messages.size() > 256)
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
      throw new IllegalStateException("Cannot save approval continuation");
    }
  }
}
