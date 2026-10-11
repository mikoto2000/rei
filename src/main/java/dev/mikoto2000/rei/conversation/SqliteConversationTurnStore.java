package dev.mikoto2000.rei.conversation;

import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.ProjectStorage;
import dev.mikoto2000.rei.storage.StorageDatabase;

public final class SqliteConversationTurnStore extends ConversationTurnStore {
  @Override public long turnCount(String conversation) {
    return database.read(connection->{try(var query=connection.prepareStatement("SELECT COUNT(*) FROM turns WHERE project_id=? AND conversation_key=?")) {
      query.setString(1,scope(conversation));query.setString(2,key(conversation));try(var rows=query.executeQuery()){rows.next();return rows.getLong(1);}
    }});
  }
  @Override public List<Turn> readRange(String conversation,long from,int limit) {
    if(from<0||limit<1||limit>100)throw new IllegalArgumentException("Invalid turn range");
    return database.read(connection->{try(var query=connection.prepareStatement("SELECT record FROM turns WHERE project_id=? AND conversation_key=? AND ordinal>=? ORDER BY ordinal LIMIT ?")) {
      query.setString(1,scope(conversation));query.setString(2,key(conversation));query.setLong(3,from);query.setInt(4,limit);
      var result=new ArrayList<Turn>();try(var rows=query.executeQuery()){while(rows.next())result.add(StorageDatabase.JSON.readValue(rows.getString(1),Turn.class));}return List.copyOf(result);
    }});
  }
  @Override public Optional<Turn> findRun(String conversation,String run) {
    return database.read(connection->{try(var query=connection.prepareStatement("SELECT record FROM turns WHERE project_id=? AND conversation_key=? AND run_id=? LIMIT 1")) {
      query.setString(1,scope(conversation));query.setString(2,key(conversation));query.setString(3,run);
      try(var rows=query.executeQuery()){return rows.next()?Optional.of(StorageDatabase.JSON.readValue(rows.getString(1),Turn.class)):Optional.empty();}
    }});
  }
  private final StorageDatabase database;
  public SqliteConversationTurnStore(Path root){super(null);database=new StorageDatabase(root);}
  public static String scope(String conversation){String project=ProjectStorage.projectId(conversation);return project==null?"":UUID.fromString(project).toString();}
  public static String key(String conversation){return UUID.nameUUIDFromBytes(conversation.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
  public static void put(Connection connection,String scope,String key,long ordinal,Turn turn)throws Exception {
    try(var write=connection.prepareStatement("INSERT INTO turns(project_id,conversation_key,ordinal,run_id,run_order,created_seconds,created_nanos,status,source,source_id,record) VALUES(?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(project_id,conversation_key,ordinal) DO UPDATE SET status=excluded.status,record=excluded.record")) {
      write.setString(1,scope);write.setString(2,key);write.setLong(3,ordinal);write.setString(4,turn.runId());write.setBytes(5,StorageDatabase.orderKey(turn.runId()));
      write.setObject(6,turn.createdAt()==null?null:turn.createdAt().getEpochSecond());write.setObject(7,turn.createdAt()==null?null:turn.createdAt().getNano());
      write.setString(8,turn.status().name());write.setString(9,turn.source());write.setString(10,turn.sourceId());write.setString(11,StorageDatabase.JSON.writeValueAsString(turn));write.executeUpdate();
    }
  }
  private static long next(Connection connection,String scope,String key)throws SQLException {
    try(var query=connection.prepareStatement("SELECT COALESCE(MAX(ordinal),-1)+1 FROM turns WHERE project_id=? AND conversation_key=?")) {
      query.setString(1,scope);query.setString(2,key);try(var rows=query.executeQuery()){rows.next();return rows.getLong(1);}
    }
  }
  @Override public void start(AgentRunContext context,String request){start(context,request,Instant.now());}
  @Override public void start(AgentRunContext context,String request,Instant time) {
    database.transaction(connection->{String scope=scope(context.conversationId()),key=key(context.conversationId());
      put(connection,scope,key,next(connection,scope,key),new Turn(context.runId(),request,Status.RUNNING,null,time));return null;});
  }
  @Override public void startOrdered(AgentRunContext context,String request,Instant observed) {
    database.transaction(connection->{String scope=scope(context.conversationId()),key=key(context.conversationId());Instant time=Objects.requireNonNull(observed);
      try(var query=connection.prepareStatement("SELECT created_seconds,created_nanos FROM turns WHERE project_id=? AND conversation_key=? AND created_seconds IS NOT NULL ORDER BY created_seconds DESC,created_nanos DESC LIMIT 1")) {
        query.setString(1,scope);query.setString(2,key);try(var rows=query.executeQuery()){if(rows.next()){var last=Instant.ofEpochSecond(rows.getLong(1),rows.getInt(2));if(!time.isAfter(last))time=last.plusNanos(1);}}
      }
      put(connection,scope,key,next(connection,scope,key),new Turn(context.runId(),request,Status.RUNNING,null,time));return null;
    });
  }
  @Override public List<Turn> read(String conversation) {
    return database.read(connection->{try(var query=connection.prepareStatement("SELECT record FROM turns WHERE project_id=? AND conversation_key=? ORDER BY ordinal")) {
      query.setString(1,scope(conversation));query.setString(2,key(conversation));var result=new ArrayList<Turn>();
      try(var rows=query.executeQuery()){while(rows.next())result.add(StorageDatabase.JSON.readValue(rows.getString(1),Turn.class));}return List.copyOf(result);
    }});
  }
  @Override public void finish(AgentRunContext context,Status status){finish(context,status,null);}
  @Override public void finish(AgentRunContext context,Status status,String message) {
    if(status==Status.RUNNING)throw new IllegalArgumentException("Expected a terminal turn status");
    mutate(context,true,turn->new Turn(turn.runId(),turn.request(),status,message,turn.createdAt(),turn.source(),turn.sourceId(),turn.metadata()));
  }
  @FunctionalInterface private interface Mutation{Turn apply(Turn turn);}
  private void mutate(AgentRunContext context,boolean runningOnly,Mutation mutation) {
    database.transaction(connection->{String scope=scope(context.conversationId()),key=key(context.conversationId());
      try(var query=connection.prepareStatement("SELECT ordinal,record FROM turns WHERE project_id=? AND conversation_key=? AND run_id=?"+(runningOnly?" AND status='RUNNING'":""))) {
        query.setString(1,scope);query.setString(2,key);query.setString(3,context.runId());
        try(var rows=query.executeQuery()){while(rows.next())put(connection,scope,key,rows.getLong(1),mutation.apply(StorageDatabase.JSON.readValue(rows.getString(2),Turn.class)));}
      }return null;
    });
  }
  @Override public void recordMetadata(AgentRunContext context,Map<String,String> metadata) {
    mutate(context,false,turn->{var combined=new HashMap<>(turn.metadata());combined.putAll(metadata);return new Turn(turn.runId(),turn.request(),turn.status(),turn.assistantMessage(),turn.createdAt(),turn.source(),turn.sourceId(),combined);});
  }
  @Override public void appendAssistantNotification(ConversationLogEntry entry) {
    database.transaction(connection->{String scope=scope(entry.conversationId()),key=key(entry.conversationId());
      try(var query=connection.prepareStatement("SELECT 1 FROM turns WHERE project_id=? AND conversation_key=? AND source=? AND source_id=? LIMIT 1")) {
        query.setString(1,scope);query.setString(2,key);query.setString(3,ConversationLogStore.BEHAVIOR_NOTIFICATION);query.setString(4,Objects.requireNonNull(entry.sourceId()));
        try(var rows=query.executeQuery()){if(rows.next())return null;}
      }
      put(connection,scope,key,next(connection,scope,key),new Turn("behavior:"+entry.sourceId(),"",Status.COMPLETED,entry.content(),entry.timestamp().toInstant(),entry.source(),entry.sourceId(),entry.metadata()));return null;
    });
  }
  @Override public List<SessionTurn> findTurns(String conversation,CursorKey after,int limit) {
    if(limit<1||limit>101)throw new IllegalArgumentException("Invalid fetch limit");
    return database.read(connection->{String sql="SELECT record FROM turns WHERE project_id=? AND conversation_key=? AND created_seconds IS NOT NULL"
        +(after==null?"":" AND (created_seconds,created_nanos,run_order)>(?,?,?)")
        +" ORDER BY created_seconds,created_nanos,run_order,ordinal LIMIT ?";
      try(var query=connection.prepareStatement(sql)) {
        query.setString(1,scope(conversation));query.setString(2,key(conversation));int index=3;
        if(after!=null){query.setLong(index++,after.time().getEpochSecond());query.setInt(index++,after.time().getNano());query.setBytes(index++,StorageDatabase.orderKey(after.id()));}
        query.setInt(index,limit);var result=new ArrayList<SessionTurn>();
        try(var rows=query.executeQuery()){while(rows.next()){var turn=StorageDatabase.JSON.readValue(rows.getString(1),Turn.class);result.add(new SessionTurn(turn.runId(),turn.request(),turn.assistantMessage(),turn.createdAt(),turn.source(),turn.sourceId(),turn.metadata()));}}return List.copyOf(result);
      }
    });
  }
}
