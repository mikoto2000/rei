package dev.mikoto2000.rei.core.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** A durable receipt is claimed before dispatch; an interrupted claim is never replayed. */
@Repository
public class ApprovalContinuationStore {
  public record StoredMessage(
      String kind,
      String text,
      List<AssistantMessage.ToolCall> calls,
      List<ToolResponseMessage.ToolResponse> responses) {}

  public record Document(
      AgentRunContext owner,
      String request,
      List<StoredMessage> history,
      RunContinuation.PendingTools pending,
      OutputLimitRunBudget.Snapshot budget,
      boolean managedParent) {}

  public record Entry(
      String approvalId, String runId, String state, String document, long pid, String started) {}

  private final JdbcClient db;
  private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

  public ApprovalContinuationStore(@Qualifier("memoryConsolidationDataSource") DataSource source) {
    db = JdbcClient.create(source);
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
                    return new StoredMessage("TOOL", "", List.of(), t.getResponses());
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

  private static final org.springframework.jdbc.core.RowMapper<Entry> ROW =
      (rs, n) ->
          new Entry(
              rs.getString("approval"),
              rs.getString("run"),
              rs.getString("state"),
              rs.getString("document"),
              rs.getLong("pid"),
              rs.getString("started"));

  public Optional<Entry> find(String id) {
    return db.sql("SELECT * FROM approval_continuations WHERE approval=?")
        .param(id)
        .query(ROW)
        .optional();
  }

  public Optional<Entry> resuming(String run) {
    return db.sql("SELECT * FROM approval_continuations WHERE run=? AND state='RESUMING'")
        .param(run)
        .query(ROW)
        .optional();
  }

  public List<String> visible(String project) {
    return db.sql(
            "SELECT approval FROM approval_continuations WHERE json_extract(document,'$.owner.projectId')=? AND state IN ('WAITING_APPROVAL','QUEUED','RESUMING','UNKNOWN','EXPIRED','DENY_QUEUED') ORDER BY rowid DESC LIMIT 256")
        .param(project)
        .query(String.class)
        .list();
  }

  public List<Entry> active() {
    return db.sql(
            "SELECT * FROM approval_continuations WHERE state IN ('WAITING_APPROVAL','QUEUED','RESUMING','DENY_QUEUED') ORDER BY approval LIMIT 256")
        .query(ROW)
        .list();
  }

  public void deny(String id) {
    db.sql(
            "UPDATE approval_continuations SET state='DENY_QUEUED' WHERE approval=? AND state='WAITING_APPROVAL'")
        .param(id)
        .update();
  }

  public Document document(Entry entry) {
    try {
      return json.readValue(entry.document(), Document.class);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Invalid approval continuation", error);
    }
  }

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
                              ToolResponseMessage.builder().responses(m.responses()).build();
                          default ->
                              throw new IllegalStateException("Invalid continuation message");
                        })
            .toList(),
        d.budget(),
        d.pending());
  }

  public boolean queue(String id) {
    return db.sql(
                "UPDATE approval_continuations SET state='QUEUED' WHERE approval=? AND state='WAITING_APPROVAL'")
            .param(id)
            .update()
        == 1;
  }

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

  public void state(String id, String state) {
    if (!Set.of("COMPLETED", "CANCELLED", "DENIED", "EXPIRED", "UNKNOWN").contains(state))
      throw new IllegalArgumentException("Invalid continuation terminal state");
    db.sql(
            "UPDATE approval_continuations SET state=? WHERE approval=? AND state IN ('WAITING_APPROVAL','QUEUED','RESUMING','DENY_QUEUED')")
        .params(state, id)
        .update();
  }

  public void finishRun(String run, String state) {
    for (var entry : active()) if (entry.runId().equals(run)) state(entry.approvalId(), state);
  }

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
        if (encoded.length() > 1048576 || messages.size() > 256)
          throw new IllegalArgumentException("Approval guidance capacity exceeded");
        db.sql("UPDATE approval_continuations SET document=? WHERE approval=? AND state=?")
            .params(encoded, entry.approvalId(), entry.state())
            .update();
      }
  }

  public boolean ownerAlive(Entry entry) {
    return entry.pid() > 0
        && entry.started() != null
        && ProcessHandle.of(entry.pid())
            .filter(ProcessHandle::isAlive)
            .flatMap(p -> p.info().startInstant())
            .map(t -> t.toString().equals(entry.started()))
            .orElse(false);
  }

  private String encode(Document d) {
    try {
      return json.writeValueAsString(d);
    } catch (java.io.IOException error) {
      throw new IllegalStateException("Cannot save approval continuation", error);
    }
  }
}
