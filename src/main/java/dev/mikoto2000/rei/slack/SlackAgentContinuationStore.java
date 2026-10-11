package dev.mikoto2000.rei.slack;

import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

/** Persist only the non-system conversation and exact ToolCall/ToolResponse pairs. */
public final class SlackAgentContinuationStore {
  private final JdbcClient db;private final ObjectMapper json=new ObjectMapper();
  public record StoredMessage(String kind,String text,List<AssistantMessage.ToolCall> calls,List<ToolResponseMessage.ToolResponse> responses) {}
  public record Document(String run,String project,String root,String session,String request,List<StoredMessage> messages,OutputLimitRunBudget.Snapshot budget) {}
  public SlackAgentContinuationStore(DataSource source){db=JdbcClient.create(source);db.sql("CREATE TABLE IF NOT EXISTS slack_agent_continuations(run TEXT PRIMARY KEY,document TEXT NOT NULL,state TEXT NOT NULL)").update();}
  public void save(AgentRunContext o,String request,List<Message> history,OutputLimitRunBudget.Snapshot budget) {
    var messages=history.stream().filter(m->!(m instanceof SystemMessage)).map(m->{
      if(m instanceof AssistantMessage a)return new StoredMessage("ASSISTANT",SlackReplies.safe(Objects.toString(a.getText(),"")),a.getToolCalls(),List.of());
      if(m instanceof ToolResponseMessage t)return new StoredMessage("TOOL","",List.of(),t.getResponses().stream().map(r->new ToolResponseMessage.ToolResponse(r.id(),r.name(),SlackReplies.safe(r.responseData()))).toList());
      if(m instanceof UserMessage)return new StoredMessage("USER",SlackReplies.safe(m.getText()),List.of(),List.of());
      throw new IllegalArgumentException("Unsupported continuation message");
    }).toList();
    try {
      String document=json.writeValueAsString(new Document(o.runId(),o.projectId(),o.projectRoot().toString(),o.conversationId(),SlackReplies.safe(request),messages,budget));
      if(document.length()>524288||messages.size()>256)throw new IllegalArgumentException("Continuation capacity exceeded");
      if(db.sql("INSERT INTO slack_agent_continuations VALUES(?,?,'WAITING') ON CONFLICT(run) DO UPDATE SET document=excluded.document,state='WAITING' WHERE state='RESUMING'").params(o.runId(),document).update()!=1)throw new IllegalStateException("Continuation already saved or stopped");
    }catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Cannot save continuation");}
  }
  public Document document(String run){try{return json.readValue(db.sql("SELECT document FROM slack_agent_continuations WHERE run=?").param(run).query(String.class).single(),Document.class);}catch(java.io.IOException error){throw new IllegalStateException("Invalid saved continuation");}}
  public RunContinuation.Saved get(AgentRunContext o){var d=document(o.runId());if(!Objects.equals(d.project(),o.projectId())||!d.root().equals(o.projectRoot().toString())||!d.session().equals(o.conversationId()))throw new IllegalArgumentException("Continuation ownership mismatch");
    var messages=d.messages().stream().map(m->(Message)switch(m.kind()){
      case "USER"->new UserMessage(m.text());case "ASSISTANT"->AssistantMessage.builder().content(m.text()).toolCalls(m.calls()).build();
      case "TOOL"->ToolResponseMessage.builder().responses(m.responses()).build();default->throw new IllegalStateException("Invalid saved message kind");
    }).toList();return new RunContinuation.Saved(d.request(),messages,d.budget());
  }
  public String state(String run){return db.sql("SELECT state FROM slack_agent_continuations WHERE run=?").param(run).query(String.class).optional().orElse(null);}
  public boolean claim(String run){return db.sql("UPDATE slack_agent_continuations SET state='RESUMING' WHERE run=? AND state='WAITING'").param(run).update()==1;}
  public List<String> waiting(){return db.sql("SELECT run FROM slack_agent_continuations WHERE state='WAITING' ORDER BY run LIMIT 64").query(String.class).list();}
  public void cancel(String run){db.sql("UPDATE slack_agent_continuations SET state='CANCELLED' WHERE run=? AND state IN ('WAITING','RESUMING')").param(run).update();}
  public void complete(String run){db.sql("UPDATE slack_agent_continuations SET state='COMPLETED' WHERE run=? AND state='RESUMING'").param(run).update();}
  public void unknown(String run){db.sql("UPDATE slack_agent_continuations SET state='UNKNOWN' WHERE run=? AND state IN ('WAITING','RESUMING')").param(run).update();}
  public void recover(){db.sql("UPDATE slack_agent_continuations SET state='UNKNOWN' WHERE state='RESUMING'").update();}
}
