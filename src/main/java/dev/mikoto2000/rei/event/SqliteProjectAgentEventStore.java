package dev.mikoto2000.rei.event;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.storage.*;

/** Indexed individual events; project counters survive later logical deletion. */
public final class SqliteProjectAgentEventStore extends ProjectAgentEventStore {
  @Override public Optional<AgentEvent> findEvent(String project,String id) {
    return database.read(db->{try(var query=db.prepareStatement("SELECT record FROM agent_events WHERE project_id=? AND id=? LIMIT 1")) {
      query.setString(1,scope(project));query.setString(2,id);try(var rows=query.executeQuery()){return rows.next()?Optional.of(json.readValue(rows.getString(1),AgentEvent.class)):Optional.empty();}
    }});
  }
  public static final int MAX_RECORD_BYTES=1024*1024;
  private static final long READ_BUDGET=16L*1024*1024;
  private final StorageDatabase database;
  private final ObjectMapper json=EventJsonCodec.mapper();
  public SqliteProjectAgentEventStore(Path root){super(root);database=new StorageDatabase(root);}
  public static String scope(String project){return UUID.fromString(project).toString();}
  @Override public AgentEvent append(AgentEvent event){String project=scope(event.projectId());return database.transaction(db->{
    long previous;try(var query=db.prepareStatement("SELECT sequence FROM event_sequences WHERE project_id=?")){query.setString(1,project);try(var rows=query.executeQuery()){previous=rows.next()?rows.getLong(1):0;}}
    long next=Math.addExact(previous,1);var stored=new AgentEvent(event.id(),next,event.timestamp(),event.type(),event.version(),event.sessionId(),event.turnId(),event.runId(),event.correlationId(),event.parentEventId(),event.payload(),event.projectId());
    String record=json.writeValueAsString(stored);put(db,stored,record,true,null,null);
    try(var update=db.prepareStatement("INSERT INTO event_sequences VALUES(?,?) ON CONFLICT(project_id) DO UPDATE SET sequence=excluded.sequence")){update.setString(1,project);update.setLong(2,next);update.executeUpdate();}return stored;
  });}
  public static void put(Connection db,AgentEvent event,String record,boolean replayable,Long start,Long end)throws Exception {
    String project=scope(event.projectId());byte[] bytes=record.getBytes(StandardCharsets.UTF_8);
    if(bytes.length>MAX_RECORD_BYTES||event.id().length()>4096||event.sessionId()!=null&&event.sessionId().length()>4096||event.runId()!=null&&event.runId().length()>4096||event.sequence()<1)throw new IllegalArgumentException("Event exceeds supported record/identity bounds");
    String turnScope=event.sessionId()==null?null:dev.mikoto2000.rei.core.project.ProjectStorage.projectId(event.sessionId());
    if(turnScope!=null)turnScope=scope(turnScope);else if(event.sessionId()!=null)turnScope="";
    String key=event.sessionId()==null?null:UUID.nameUUIDFromBytes(event.sessionId().getBytes(StandardCharsets.UTF_8)).toString();
    try(var insert=db.prepareStatement("INSERT INTO agent_events(project_id,sequence,id,event_type,created_seconds,created_nanos,session_id,run_id,turn_scope,conversation_key,legacy_replayable,legacy_start,legacy_end,record) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      insert.setString(1,project);insert.setLong(2,event.sequence());insert.setString(3,event.id());insert.setString(4,event.type().name());insert.setLong(5,event.timestamp().getEpochSecond());insert.setInt(6,event.timestamp().getNano());insert.setString(7,event.sessionId());insert.setString(8,event.runId());insert.setString(9,turnScope);insert.setString(10,key);insert.setBoolean(11,replayable);insert.setObject(12,start);insert.setObject(13,end);insert.setString(14,record);insert.executeUpdate();
    }
    StorageObjectRegistry.registerEvent(db,event,record);
  }
  @Override public long lastSequence(String project){return database.read(db->{try(var query=db.prepareStatement("SELECT sequence FROM event_sequences WHERE project_id=?")){query.setString(1,scope(project));try(var rows=query.executeQuery()){return rows.next()?rows.getLong(1):0;}}});}
  @Override public List<AgentEvent> recent(String project,int limit){checkLimit(limit);return database.read(db->{var result=read(db,project,0,limit,true,false).events();var copy=new ArrayList<>(result);Collections.reverse(copy);return List.copyOf(copy);});}
  @Override public List<AgentEvent> readAfter(String project,long after,int limit){checkLimit(limit);if(after<0)throw new IllegalArgumentException("Negative event sequence");return database.read(db->read(db,project,after,limit,false,false).events());}
  @Override public Page readPage(String project,long after,boolean discard){if(after<0||discard)throw new IllegalArgumentException("SQLite replay requires a sequence cursor with no partial-line state");return database.read(db->read(db,project,after,128,false,true));}
  private Page read(Connection db,String project,long after,int limit,boolean reverse,boolean replay)throws Exception {
    var result=new ArrayList<AgentEvent>();long bytes=0,next=after;
    String sql="SELECT sequence,legacy_replayable,length(CAST(record AS BLOB)),record FROM agent_events WHERE project_id=? AND sequence>? ORDER BY sequence "+(reverse?"DESC":"ASC")+" LIMIT ?";
    try(var query=db.prepareStatement(sql)){query.setString(1,scope(project));query.setLong(2,after);query.setInt(3,limit);try(var rows=query.executeQuery()) {
      while(rows.next()) {
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Event read interrupted");
        if(replay&&!rows.getBoolean(2)){next=rows.getLong(1);continue;}
        long size=rows.getLong(3);if(size>MAX_RECORD_BYTES)throw new IllegalStateException("Stored event exceeds record limit");if(size>READ_BUDGET-bytes)break;
        result.add(json.readValue(rows.getString(4),AgentEvent.class));bytes+=size;next=rows.getLong(1);
      }
    }}return new Page(List.copyOf(result),next,false);
  }
  @Override public Map<String,String> referenceStatus(String project,Set<String> ids){if(ids.size()>10000)throw new IllegalArgumentException("Reference lookup exceeds budget");return database.read(db->{var result=new HashMap<String,String>();try(var query=db.prepareStatement("SELECT 1 FROM agent_events WHERE project_id=? AND id=?")){for(String id:ids){query.setString(1,scope(project));query.setString(2,id);try(var rows=query.executeQuery()){result.put(id,rows.next()?"available":"not-found");}}}return Map.copyOf(result);});}
  private static void checkLimit(int limit){if(limit<1||limit>1000)throw new IllegalArgumentException("Event limit must be 1..1000");}
}
