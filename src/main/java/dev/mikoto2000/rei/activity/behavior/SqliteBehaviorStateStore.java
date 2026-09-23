package dev.mikoto2000.rei.activity.behavior;

import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Lazy, small checkpoint and bounded transition history; never writes every NONE assessment. */
public final class SqliteBehaviorStateStore implements BehaviorStateStore {
  private final DataSource dataSource;
  private final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
  private boolean initialized;
  public SqliteBehaviorStateStore(DataSource dataSource) {this.dataSource=dataSource;}
  private void initialize() throws Exception {
    if(initialized) return;
    try(var c=dataSource.getConnection();var s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS activity_behavior_state (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)");
      s.execute("CREATE TABLE IF NOT EXISTS activity_behavior_transitions (id INTEGER PRIMARY KEY AUTOINCREMENT, payload TEXT NOT NULL)");
      initialized=true;
    }
  }
  @Override public synchronized BehaviorState load() {
    try {
      initialize();try(var c=dataSource.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT payload FROM activity_behavior_state WHERE id=1")) {
        return r.next()?mapper.readValue(r.getString(1),BehaviorState.class):BehaviorState.empty();
      }
    } catch(Exception e) {throw new IllegalStateException("Behavior state read failed",e);}
  }
  @Override public synchronized void save(BehaviorState state,BehaviorAssessment assessment) {
    try {
      initialize();try(var c=dataSource.getConnection()) {
        c.setAutoCommit(false);
        try {
          try(var s=c.prepareStatement("INSERT INTO activity_behavior_state(id,payload) VALUES(1,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")) {
            s.setString(1,mapper.writeValueAsString(state));s.executeUpdate();
          }
          try(var s=c.prepareStatement("INSERT INTO activity_behavior_transitions(payload) VALUES(?)")) {s.setString(1,mapper.writeValueAsString(assessment));s.executeUpdate();}
          try(var s=c.createStatement()) {s.executeUpdate("DELETE FROM activity_behavior_transitions WHERE id NOT IN (SELECT id FROM activity_behavior_transitions ORDER BY id DESC LIMIT 100)");}
          c.commit();
        } catch(Exception e) {c.rollback();throw e;}
      }
    } catch(Exception e) {throw new IllegalStateException("Behavior state write failed",e);}
  }
}
