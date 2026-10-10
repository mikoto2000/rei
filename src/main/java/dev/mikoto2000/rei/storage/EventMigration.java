package dev.mikoto2000.rei.storage;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import dev.mikoto2000.rei.event.*;

/** Strict streamed JSONL import and byte-boundary to sequence conversion in one target transaction. */
final class EventMigration {
  private record LegacyCursor(long offset,boolean discard) {}
  static void apply(Connection db,Path root)throws Exception {
    try(var sql=db.createStatement()) {
      sql.execute("CREATE TABLE agent_events(project_id TEXT NOT NULL,sequence INTEGER NOT NULL,id TEXT NOT NULL,event_type TEXT NOT NULL,created_seconds INTEGER NOT NULL,created_nanos INTEGER NOT NULL,session_id TEXT,run_id TEXT,turn_scope TEXT,conversation_key TEXT,legacy_replayable INTEGER NOT NULL,legacy_start INTEGER,legacy_end INTEGER,record TEXT NOT NULL,PRIMARY KEY(project_id,sequence),UNIQUE(project_id,id))");
      sql.execute("CREATE INDEX agent_events_time ON agent_events(project_id,created_seconds,created_nanos,sequence)");
      sql.execute("CREATE INDEX agent_events_run ON agent_events(project_id,run_id,sequence)");
      sql.execute("CREATE TABLE event_sequences(project_id TEXT PRIMARY KEY,sequence INTEGER NOT NULL)");
      sql.execute("CREATE TABLE event_cursors(project_id TEXT PRIMARY KEY,sequence INTEGER NOT NULL,generation INTEGER NOT NULL,legacy_offset INTEGER,legacy_discard INTEGER,converted INTEGER NOT NULL)");
      sql.execute("CREATE TABLE event_imports(path TEXT PRIMARY KEY,project_id TEXT NOT NULL UNIQUE,source_sha256 TEXT NOT NULL,records INTEGER NOT NULL,row_sha256 TEXT NOT NULL,source_bytes INTEGER NOT NULL)");
    }
    importCursorsAndReferences(db,root);
    Path projects=root.resolve("projects");StorageBackup.requireSafePath(projects);
    if(Files.exists(projects,LinkOption.NOFOLLOW_LINKS))try(var entries=Files.newDirectoryStream(projects)) {
      int count=0;for(Path project:entries){if(++count>1000000)throw new IOException("Event project limit exceeded");String scope=SqliteProjectAgentEventStore.scope(project.getFileName().toString());Path source=project.resolve("events/events.jsonl");StorageBackup.requireSafePath(source);if(Files.exists(source,LinkOption.NOFOLLOW_LINKS))importFile(db,root,source,scope);}
    }
    try(var sql=db.createStatement()) {
      sql.executeUpdate("UPDATE event_cursors SET converted=1 WHERE converted=0 AND legacy_offset=0");
      try(var rows=sql.executeQuery("SELECT project_id FROM event_cursors WHERE converted=0 LIMIT 1")){if(rows.next())throw new IOException("Event cursor has no corresponding verified source: "+rows.getString(1));}
    }
  }
  private static boolean table(Connection db,String name)throws SQLException {try(var query=db.prepareStatement("SELECT 1 FROM sqlite_schema WHERE type='table' AND name=?")){query.setString(1,name);try(var rows=query.executeQuery()){return rows.next();}}}
  private static void importCursorsAndReferences(Connection db,Path root)throws Exception {
    Path file=root.resolve("memory-consolidation.db");StorageBackup.requireSafePath(file);if(Files.notExists(file))return;
    try(var legacy=StorageBackup.readOnly(file)) {
      if(table(legacy,"agent_schedule_event_cursors"))try(var query=legacy.createStatement();var rows=query.executeQuery("SELECT project,offset,discard,generation,typeof(offset),typeof(discard),typeof(generation) FROM agent_schedule_event_cursors")) {
        long count=0;try(var insert=db.prepareStatement("INSERT INTO event_cursors VALUES(?,0,?,?,?,0)")) {
          while(rows.next()) {
            if(++count>1000000||rows.getLong(2)<0||rows.getLong(4)<0||!rows.getString(5).equals("integer")||!rows.getString(6).equals("integer")||!rows.getString(7).equals("integer")||rows.getInt(3)<0||rows.getInt(3)>1)throw new IOException("Unsupported legacy event cursor");
            insert.setString(1,SqliteProjectAgentEventStore.scope(rows.getString(1)));insert.setLong(2,rows.getLong(4));insert.setLong(3,rows.getLong(2));insert.setInt(4,rows.getInt(3));insert.executeUpdate();
          }
        }
      }
      if(table(legacy,"agent_schedules"))try(var query=legacy.createStatement();var rows=query.executeQuery("SELECT DISTINCT project FROM agent_schedules WHERE status='WAITING_EVENT'")) {
        int count=0;while(rows.next()) {
          if(++count>1000000)throw new IOException("Waiting project limit exceeded");String project=SqliteProjectAgentEventStore.scope(rows.getString(1));
          try(var check=db.prepareStatement("SELECT 1 FROM event_cursors WHERE project_id=?")){check.setString(1,project);try(var cursor=check.executeQuery()){if(!cursor.next())throw new IOException("Waiting project has no legacy replay cursor; source retained: "+project);}}
        }
      }
      if(table(legacy,"agent_schedules")&&table(legacy,"agent_schedule_events"))try(var query=legacy.createStatement();var rows=query.executeQuery("SELECT s.project,e.id,e.matched_event FROM agent_schedule_events e JOIN agent_schedules s ON s.id=e.id WHERE e.matched_event IS NOT NULL")) {
        int count=0;while(rows.next()){if(++count>1000000)throw new IOException("Schedule reference limit exceeded");StorageObjectRegistry.addReference(db,"event:"+SqliteProjectAgentEventStore.scope(rows.getString(1))+":"+rows.getString(3),"SCHEDULE_MATCH",rows.getString(2));}
      }
    }
  }
  private static LegacyCursor cursor(Connection db,String scope)throws SQLException {
    try(var query=db.prepareStatement("SELECT legacy_offset,legacy_discard FROM event_cursors WHERE project_id=?")){query.setString(1,scope);try(var rows=query.executeQuery()){return rows.next()?new LegacyCursor(rows.getLong(1),rows.getBoolean(2)):null;}}
  }
  private static void converted(Connection db,String scope,long sequence)throws SQLException {
    try(var update=db.prepareStatement("UPDATE event_cursors SET sequence=?,converted=1 WHERE project_id=?")){update.setLong(1,sequence);update.setString(2,scope);update.executeUpdate();}
  }
  private static void importFile(Connection db,Path root,Path source,String scope)throws Exception {
    if(!Files.isRegularFile(source,LinkOption.NOFOLLOW_LINKS))throw new IOException("Event source is not a regular file");
    String sourceHash=StorageBackup.hash(source);long size=Files.size(source);var cursor=cursor(db,scope);boolean mapped=cursor==null;long[] previous={0},count={0};
    if(cursor!=null&&cursor.offset()==0&&cursor.discard())throw new IOException("Ambiguous discard cursor at beginning of event source: "+scope);
    if(cursor!=null&&(cursor.offset()==0||cursor.offset()>size)){converted(db,scope,0);mapped=true;}
    var expected=MessageDigest.getInstance("SHA-256");var mapper=EventJsonCodec.mapper();
    long offset=0,start=0;var line=new ByteArrayOutputStream(1024);
    try(var input=new BufferedInputStream(Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS),65536)) {
      int value;while((value=input.read())!=-1) {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Event import interrupted");offset=Math.addExact(offset,1);
        if(value!='\n'){if(line.size()>=SqliteProjectAgentEventStore.MAX_RECORD_BYTES)throw new IOException("Event source line exceeds 1 MiB: "+root.relativize(source)+" offset="+start);line.write(value);continue;}
        byte[] bytes=line.toByteArray();String record=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        long before=previous[0];boolean replayable=bytes.length<65536;
        if(!record.isBlank()) {
          AgentEvent event;
          try(var parser=mapper.createParser(record)){event=mapper.readValue(parser,AgentEvent.class);if(parser.nextToken()!=null)throw new IOException("Trailing event JSON");}
          if(!SqliteProjectAgentEventStore.scope(event.projectId()).equals(scope)||event.sequence()<=previous[0])throw new IOException("Event project/sequence mismatch at "+root.relativize(source)+" offset="+start);
          if(++count[0]>1000000)throw new IOException("Event record limit exceeded");
          SqliteProjectAgentEventStore.put(db,event,record,replayable,start,offset);expected.update(bytes);expected.update((byte)'\n');previous[0]=event.sequence();
        }
        if(!mapped&&cursor!=null) {
          if(cursor.offset()==offset){if(cursor.discard())throw new IOException("Ambiguous discard cursor at complete line boundary: "+scope);converted(db,scope,previous[0]);mapped=true;}
          else if(cursor.offset()>start&&cursor.offset()<offset) {
            if(!cursor.discard()||replayable)throw new IOException("Ambiguous legacy event cursor inside a normal line: "+scope);
            converted(db,scope,before);mapped=true;
          }
        }
        line.reset();start=offset;
      }
    }
    if(line.size()!=0)throw new IOException("Incomplete event JSONL tail: "+root.relativize(source)+" offset="+start);
    if(!mapped)throw new IOException("Unmapped legacy event cursor: "+scope);
    if(offset!=size||!StorageBackup.hash(source).equals(sourceHash))throw new IOException("Event source changed during import");
    var actual=MessageDigest.getInstance("SHA-256");long rowsCount=0;
    try(var query=db.prepareStatement("SELECT record FROM agent_events WHERE project_id=? ORDER BY sequence")){query.setString(1,scope);try(var rows=query.executeQuery()){while(rows.next()){actual.update(rows.getString(1).getBytes(StandardCharsets.UTF_8));actual.update((byte)'\n');rowsCount++;}}}
    byte[] actualHash=actual.digest();if(rowsCount!=count[0]||!MessageDigest.isEqual(expected.digest(),actualHash))throw new IOException("Event row count/hash mismatch");
    try(var insert=db.prepareStatement("INSERT INTO event_sequences VALUES(?,?)")){insert.setString(1,scope);insert.setLong(2,previous[0]);insert.executeUpdate();}
    try(var insert=db.prepareStatement("INSERT INTO event_imports VALUES(?,?,?,?,?,?)")){insert.setString(1,root.relativize(source).toString().replace('\\','/'));insert.setString(2,scope);insert.setString(3,sourceHash);insert.setLong(4,count[0]);insert.setString(5,HexFormat.of().formatHex(actualHash));insert.setLong(6,size);insert.executeUpdate();}
  }
  static void verify(Connection db)throws SQLException {try(var sql=db.createStatement()) {
    sql.executeQuery("SELECT project_id,sequence,id,event_type,created_seconds,created_nanos,session_id,run_id,turn_scope,conversation_key,legacy_replayable,legacy_start,legacy_end,record FROM agent_events LIMIT 0").close();
    sql.executeQuery("SELECT project_id,sequence FROM event_sequences LIMIT 0").close();
    sql.executeQuery("SELECT project_id,sequence,generation,legacy_offset,legacy_discard,converted FROM event_cursors LIMIT 0").close();
    sql.executeQuery("SELECT path,project_id,source_sha256,records,row_sha256,source_bytes FROM event_imports LIMIT 0").close();
  }}
}
