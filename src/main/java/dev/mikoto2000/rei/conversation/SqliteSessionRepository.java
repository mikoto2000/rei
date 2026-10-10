package dev.mikoto2000.rei.conversation;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.storage.StorageDatabase;

/** Session mutations touch one row; keyset queries use indexed time and Java string order. */
public final class SqliteSessionRepository implements SessionRepository {
  private final StorageDatabase database;
  private volatile List<SessionMetadata> completion=List.of();
  private record Previous(SessionMetadata metadata,long revision){}
  public SqliteSessionRepository(Path root){database=new StorageDatabase(root);refreshCompletion();}
  @Override public List<SessionMetadata> completionSnapshot(){return completion;}
  private void refreshCompletion(){completion=findPage(null,null,101);}
  private static Previous previous(Connection connection,String id)throws Exception {
    try(var query=connection.prepareStatement("SELECT record,revision FROM sessions WHERE session_id=?")) {
      query.setString(1,id);try(var rows=query.executeQuery()) {
        return rows.next()?new Previous(StorageDatabase.JSON.readValue(rows.getString(1),SessionMetadata.class),rows.getLong(2)):null;
      }
    }
  }
  @Override public Optional<SessionMetadata> findById(String id){return database.read(connection->{var row=previous(connection,id);return row==null?Optional.empty():Optional.of(row.metadata());});}
  @Override public List<SessionMetadata> findPage(String project,CursorKey after,int limit) {
    if(limit<1||limit>101)throw new IllegalArgumentException("Invalid fetch limit");
    return database.read(connection->{
      String sql="SELECT record FROM sessions WHERE 1=1"+(project==null?"":" AND project_id=?")
          +(after==null?"":" AND (updated_sort_seconds,updated_sort_nanos,id_order)>(?,?,?)")
          +" ORDER BY updated_sort_seconds,updated_sort_nanos,id_order LIMIT ?";
      try(var query=connection.prepareStatement(sql)) {
        int index=1;if(project!=null)query.setString(index++,project);
        if(after!=null){query.setLong(index++,-after.time().getEpochSecond());query.setInt(index++,-after.time().getNano());query.setBytes(index++,StorageDatabase.orderKey(after.id()));}
        query.setInt(index,limit);var result=new ArrayList<SessionMetadata>();
        try(var rows=query.executeQuery()){while(rows.next())result.add(StorageDatabase.JSON.readValue(rows.getString(1),SessionMetadata.class));}
        return List.copyOf(result);
      }
    });
  }
  public static void put(Connection connection,SessionMetadata metadata,long revision,Long importOrder)throws Exception {
    try(var write=connection.prepareStatement("INSERT INTO sessions(session_id,project_id,created_seconds,created_nanos,updated_seconds,updated_nanos,id_order,record,revision,import_order) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(session_id) DO UPDATE SET updated_seconds=excluded.updated_seconds,updated_nanos=excluded.updated_nanos,record=excluded.record,revision=excluded.revision")) {
      write.setString(1,metadata.sessionId());write.setString(2,metadata.projectId());write.setLong(3,metadata.createdAt().getEpochSecond());write.setInt(4,metadata.createdAt().getNano());
      write.setLong(5,metadata.updatedAt().getEpochSecond());write.setInt(6,metadata.updatedAt().getNano());write.setBytes(7,StorageDatabase.orderKey(metadata.sessionId()));
      write.setString(8,StorageDatabase.JSON.writeValueAsString(metadata));write.setLong(9,revision);write.setObject(10,importOrder);write.executeUpdate();
    }
  }
  @Override public void accept(SessionMetadata metadata,Runnable enqueue) {
    synchronized(database.writer) {
      Previous old=database.transaction(connection->{
        var previous=previous(connection,metadata.sessionId());SessionMetadata next=metadata;
        if(previous!=null) {
          var row=previous.metadata();
          if(!row.projectId().equals(metadata.projectId())||!row.title().equals(metadata.title())||!row.createdAt().equals(metadata.createdAt()))throw new IllegalArgumentException("Immutable session metadata");
          next=metadata.touched(row.updatedAt());
        }
        put(connection,next,previous==null?1:Math.addExact(previous.revision(),1),null);return previous;
      });
      try{refreshCompletion();enqueue.run();}
      catch(RuntimeException|Error error) {
        try {
          database.transaction(connection->{
            var current=previous(connection,metadata.sessionId());long expected=old==null?1:old.revision()+1;
            if(current==null||current.revision()!=expected)throw new IllegalStateException("Session changed during failed admission; refusing to overwrite newer metadata");
            if(old==null)try(var delete=connection.prepareStatement("DELETE FROM sessions WHERE session_id=?")){delete.setString(1,metadata.sessionId());delete.executeUpdate();}
            else put(connection,old.metadata(),old.revision(),null);
            return null;
          });refreshCompletion();
        }catch(RuntimeException rollback){error.addSuppressed(rollback);}throw error;
      }
    }
  }
}
