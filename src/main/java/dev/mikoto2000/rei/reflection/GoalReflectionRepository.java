package dev.mikoto2000.rei.reflection;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.goal.GoalRepository;

/** Append-only observations and conservative review suggestions, separate from validated memories. */
@Repository
public class GoalReflectionRepository {
  public record Item(String id,String projectId,String sessionId,String goalId,String runId,String goalStatus,
      String expectedFile,String expectedSha256,String actual,String actualReason,String gap,String nextAction,String sourceEvent,Instant createdAt) {}
  private final JdbcClient db;
  private final Clock clock;
  public GoalReflectionRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS goal_reflections(id TEXT PRIMARY KEY,project TEXT NOT NULL,session TEXT NOT NULL,goal TEXT NOT NULL,run TEXT NOT NULL,status TEXT NOT NULL,expected_file TEXT NOT NULL,expected_digest TEXT NOT NULL,actual TEXT NOT NULL,actual_reason TEXT NOT NULL,gap TEXT NOT NULL,next_action TEXT NOT NULL,source_event TEXT NOT NULL,created INTEGER NOT NULL,UNIQUE(project,goal,run,status))").update();
    db.sql("CREATE INDEX IF NOT EXISTS goal_reflections_project ON goal_reflections(project,created)").update();
  }
  private static final org.springframework.jdbc.core.RowMapper<Item> ROW=(rs,n)->new Item(rs.getString("id"),rs.getString("project"),rs.getString("session"),rs.getString("goal"),
      rs.getString("run"),rs.getString("status"),rs.getString("expected_file"),rs.getString("expected_digest"),rs.getString("actual"),rs.getString("actual_reason"),
      rs.getString("gap"),rs.getString("next_action"),rs.getString("source_event"),Instant.ofEpochMilli(rs.getLong("created")));
  Item save(GoalRepository.Goal goal,AgentEvent source,GoalLifecyclePayload snapshot,String actual,String reason,String gap,String nextAction) {
    if(!goal.projectId().equals(source.projectId())||!goal.sessionId().equals(source.sessionId())||!goal.id().equals(snapshot.goalId()))
      throw new IllegalArgumentException("Reflection source ownership does not match its Goal");
    String run=source.runId()==null?"":source.runId();
    db.sql("INSERT OR IGNORE INTO goal_reflections VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")
        .params("reflection-"+UUID.randomUUID(),goal.projectId(),goal.sessionId(),goal.id(),run,snapshot.status(),expectedFiles(goal),expectedDigests(goal),actual,reason,gap,nextAction,source.id(),clock.millis()).update();
    return db.sql("SELECT * FROM goal_reflections WHERE project=? AND goal=? AND run=? AND status=?")
        .params(goal.projectId(),goal.id(),run,snapshot.status()).query(ROW).single();
  }
  public List<Item> list(String project) {return db.sql("SELECT * FROM goal_reflections WHERE project=? ORDER BY created DESC,id LIMIT 256").param(project).query(ROW).list();}
  public Item get(String project,String id) {return db.sql("SELECT * FROM goal_reflections WHERE project=? AND id=?").params(project,id).query(ROW).optional()
      .orElseThrow(()->new IllegalArgumentException("Reflection not found in this Project"));}
  public boolean matchesCriteria(Item item,GoalRepository.Goal goal){return item.expectedFile().equals(expectedFiles(goal))&&item.expectedSha256().equals(expectedDigests(goal));}
  // Multiple criteria are ordered JSON arrays; single-file observations retain their original representation.
  private String expectedFiles(GoalRepository.Goal goal) {return goal.criteria().size()==1?goal.relativeFile():json(goal.criteria().stream().map(GoalRepository.FileCriterion::relativeFile).toList());}
  private String expectedDigests(GoalRepository.Goal goal) {
    if(goal.criteria().stream().anyMatch(GoalRepository.FileCriterion::jsonCriterion))return json(goal.criteria());
    return goal.criteria().size()==1?goal.sha256():json(goal.criteria().stream().map(GoalRepository.FileCriterion::sha256).toList());
  }
  private String json(Object value) {
    try {return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);}
    catch(com.fasterxml.jackson.core.JsonProcessingException impossible){throw new IllegalStateException(impossible);}
  }
}
