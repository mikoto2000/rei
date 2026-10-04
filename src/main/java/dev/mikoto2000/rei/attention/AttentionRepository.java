package dev.mikoto2000.rei.attention;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import dev.mikoto2000.rei.event.AgentEvent;

/** Persistent human inbox; acknowledging an item never grants permissions or dispatches a run. */
@Repository
public class AttentionRepository {
  public record Item(String id,String projectId,String sessionId,String runId,String kind,String reference,String message,String status,Instant createdAt) {}
  private final JdbcClient db;
  private final Clock clock;
  public AttentionRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS agent_attention(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,kind TEXT NOT NULL,reference TEXT NOT NULL,message TEXT NOT NULL,status TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(project,session,run,kind,reference))").update();
    db.sql("CREATE INDEX IF NOT EXISTS agent_attention_open ON agent_attention(project,status,created)").update();
  }
  private static final org.springframework.jdbc.core.RowMapper<Item> ROW=(rs,n)->new Item(rs.getString("id"),rs.getString("project"),rs.getString("session"),
      rs.getString("run"),rs.getString("kind"),rs.getString("reference"),rs.getString("message"),rs.getString("status"),Instant.ofEpochMilli(rs.getLong("created")));
  public Optional<Item> create(AgentEvent source,String kind,String reference,String message) {
    if(source.projectId()==null||source.projectId().isBlank()||source.sessionId()==null||source.sessionId().isBlank()||source.runId()==null||source.runId().isBlank())
      return Optional.empty();
    String id=UUID.randomUUID().toString();
    int inserted=db.sql("INSERT OR IGNORE INTO agent_attention VALUES(?,?,?,?,?,?,?,'OPEN',?)")
        .params(id,source.projectId(),source.sessionId(),source.runId(),kind,reference,message,clock.millis()).update();
    return inserted==1?Optional.of(get(source.projectId(),id)):Optional.empty();
  }
  public List<Item> list(String project) {return db.sql("SELECT * FROM agent_attention WHERE project=? AND status='OPEN' ORDER BY created,id LIMIT 256").param(project).query(ROW).list();}
  public Item get(String project,String id) {return db.sql("SELECT * FROM agent_attention WHERE project=? AND id=?").params(project,id).query(ROW).optional()
      .orElseThrow(()->new IllegalArgumentException("Attention item not found in this project"));}
  public void acknowledge(String project,String id) {
    get(project,id);db.sql("UPDATE agent_attention SET status='ACKNOWLEDGED' WHERE project=? AND id=? AND status='OPEN'").params(project,id).update();
  }
}
