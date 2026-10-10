package dev.mikoto2000.rei.storage;

import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;

/** Cursor units are project sequences after startup conversion, never byte offsets. */
public final class SqliteEventCursorStore {
  public record Cursor(long sequence,long generation) {}
  private final StorageDatabase database;
  public SqliteEventCursorStore(Path root){database=new StorageDatabase(root);}
  private static String scope(String project){return UUID.fromString(project).toString();}
  public Cursor get(String project){return database.read(db->get(db,scope(project)));}
  private static Cursor get(Connection db,String project)throws SQLException {
    try(var query=db.prepareStatement("SELECT sequence,generation FROM event_cursors WHERE project_id=? AND converted=1")){query.setString(1,project);try(var rows=query.executeQuery()){return rows.next()?new Cursor(rows.getLong(1),rows.getLong(2)):new Cursor(0,0);}}
  }
  public void reset(String project){String scope=scope(project);database.transaction(db->{
    long generation;try(var query=db.prepareStatement("SELECT generation FROM event_cursors WHERE project_id=?")){query.setString(1,scope);try(var rows=query.executeQuery()){generation=rows.next()?Math.addExact(rows.getLong(1),1):0;}}
    try(var update=db.prepareStatement("INSERT INTO event_cursors(project_id,sequence,generation,converted) VALUES(?,0,?,1) ON CONFLICT(project_id) DO UPDATE SET sequence=0,generation=excluded.generation,converted=1")){update.setString(1,scope);update.setLong(2,generation);update.executeUpdate();}return null;
  });}
  public boolean compareAndSet(String project,Cursor expected,long next){if(next<expected.sequence()||next<0)throw new IllegalArgumentException("Cursor cannot move backwards without a new generation");return database.transaction(db->{
    try(var update=db.prepareStatement("UPDATE event_cursors SET sequence=? WHERE project_id=? AND sequence=? AND generation=? AND converted=1 AND ?<=COALESCE((SELECT sequence FROM event_sequences WHERE project_id=?),0)")){String scope=scope(project);update.setLong(1,next);update.setString(2,scope);update.setLong(3,expected.sequence());update.setLong(4,expected.generation());update.setLong(5,next);update.setString(6,scope);return update.executeUpdate()==1;}
  });}
}
