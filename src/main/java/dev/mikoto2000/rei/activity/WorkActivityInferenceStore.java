package dev.mikoto2000.rei.activity;

import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Additive indexed supplement table. Does not rebuild sessions or mutate raw evidence. */
public class WorkActivityInferenceStore {
  private final DataSource ds;private boolean initialized;
  private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
  public WorkActivityInferenceStore(DataSource ds){this.ds=ds;}
  private synchronized void initialize() throws Exception {
    if(initialized)return;
    try(var c=ds.getConnection();var s=c.createStatement()) {
      s.execute("CREATE TABLE IF NOT EXISTS activity_work_inferences(id TEXT PRIMARY KEY, window_end INTEGER NOT NULL, payload TEXT NOT NULL)");
      s.execute("CREATE INDEX IF NOT EXISTS activity_work_inferences_time ON activity_work_inferences(window_end)");
    }initialized=true;
  }
  public synchronized void save(WorkActivityInference inference) {
    try {initialize();String payload=json.writeValueAsString(inference);if(payload.length()>65536)throw new IllegalArgumentException("Inference payload too large");
      try(var c=ds.getConnection();var s=c.prepareStatement("INSERT INTO activity_work_inferences VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET payload=excluded.payload")) {
        s.setString(1,inference.id());s.setLong(2,inference.windowEnd().toEpochMilli());s.setString(3,payload);s.executeUpdate();
      }
    }catch(Exception e){throw new IllegalStateException("Temporal inference persistence failed",e);}
  }
  public synchronized Optional<WorkActivityInference> latest() {
    try {initialize();try(var c=ds.getConnection();var s=c.createStatement();var r=s.executeQuery("SELECT payload FROM activity_work_inferences ORDER BY window_end DESC,id DESC LIMIT 1")) {
      return r.next()?Optional.of(decode(r.getString(1))):Optional.empty();
    }}catch(Exception e){throw new IllegalStateException("Temporal inference query failed",e);}
  }
  /** Each rolling inference belongs to its windowEnd instant, so midnight overlap never duplicates it. */
  public synchronized List<WorkActivityInference> findBetween(Instant start,Instant end,int limit) {
    if(!start.isBefore(end) || limit<1 || limit>500)throw new IllegalArgumentException("Invalid inference range");
    try {initialize();var result=new ArrayList<WorkActivityInference>();
      try(var c=ds.getConnection();var s=c.prepareStatement("SELECT payload FROM activity_work_inferences WHERE window_end>=? AND window_end<? ORDER BY window_end DESC,id DESC LIMIT ?")) {
        s.setLong(1,start.toEpochMilli());s.setLong(2,end.toEpochMilli());s.setInt(3,limit);
        try(var r=s.executeQuery()){while(r.next())result.add(decode(r.getString(1)));}
      }Collections.reverse(result);return List.copyOf(result);
    }catch(Exception e){throw new IllegalStateException("Temporal inference query failed",e);}
  }
  private WorkActivityInference decode(String value)throws Exception {
    if(value==null || value.length()>65536)throw new IllegalArgumentException("Inference payload too large");
    return json.readValue(value,WorkActivityInference.class);
  }
}
