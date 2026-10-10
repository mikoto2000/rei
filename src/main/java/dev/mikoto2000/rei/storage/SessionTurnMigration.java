package dev.mikoto2000.rei.storage;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;
import com.fasterxml.jackson.core.JsonToken;
import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.conversation.*;

/** Stream old arrays into individual rows, then verify their count and canonical row digest. */
final class SessionTurnMigration {
  private SessionTurnMigration(){}
  static void apply(Connection connection,Path root)throws Exception {
    try(var schema=connection.createStatement()) {
      schema.execute("CREATE TABLE sessions(session_id TEXT PRIMARY KEY,project_id TEXT NOT NULL,created_seconds INTEGER NOT NULL,created_nanos INTEGER NOT NULL,updated_seconds INTEGER NOT NULL,updated_nanos INTEGER NOT NULL,updated_sort_seconds INTEGER GENERATED ALWAYS AS (-updated_seconds) STORED,updated_sort_nanos INTEGER GENERATED ALWAYS AS (-updated_nanos) STORED,id_order BLOB NOT NULL,record TEXT NOT NULL,revision INTEGER NOT NULL,import_order INTEGER UNIQUE)");
      schema.execute("CREATE INDEX sessions_order ON sessions(updated_sort_seconds,updated_sort_nanos,id_order)");
      schema.execute("CREATE INDEX sessions_project_order ON sessions(project_id,updated_sort_seconds,updated_sort_nanos,id_order)");
      schema.execute("CREATE TABLE storage_turn_sources(project_id TEXT NOT NULL,conversation_key TEXT NOT NULL,path TEXT NOT NULL,PRIMARY KEY(project_id,conversation_key))");
      schema.execute("CREATE TABLE turns(project_id TEXT NOT NULL,conversation_key TEXT NOT NULL,ordinal INTEGER NOT NULL,run_id TEXT NOT NULL,run_order BLOB NOT NULL,created_seconds INTEGER,created_nanos INTEGER,status TEXT NOT NULL,source TEXT,source_id TEXT,record TEXT NOT NULL,PRIMARY KEY(project_id,conversation_key,ordinal))");
      schema.execute("CREATE INDEX turns_order ON turns(project_id,conversation_key,created_seconds,created_nanos,run_order,ordinal)");
      schema.execute("CREATE INDEX turns_run ON turns(project_id,conversation_key,run_id,status)");
      schema.execute("CREATE INDEX turns_notification ON turns(project_id,conversation_key,source,source_id)");
      schema.execute("CREATE TABLE storage_imports(path TEXT PRIMARY KEY,source_sha256 TEXT NOT NULL,records INTEGER NOT NULL,row_sha256 TEXT NOT NULL)");
    }
    Path sessions=root.resolve("sessions.json");
    if(Files.exists(sessions,LinkOption.NOFOLLOW_LINKS))importFile(connection,root,sessions,SessionMetadata.class,
        (row,ordinal)->SqliteSessionRepository.put(connection,row,1,ordinal),"SELECT record FROM sessions ORDER BY import_order",null,null);
    turns(connection,root,root.resolve("state/turns"),"");
    Path projects=root.resolve("projects");
    if(Files.exists(projects,LinkOption.NOFOLLOW_LINKS))try(var paths=Files.newDirectoryStream(projects)) {
      for(Path project:paths)turns(connection,root,project.resolve("state/turns"),UUID.fromString(project.getFileName().toString()).toString());
    }
  }
  private static void turns(Connection connection,Path root,Path directory,String scope)throws Exception {
    StorageBackup.requireSafePath(directory);if(Files.notExists(directory,LinkOption.NOFOLLOW_LINKS))return;
    try(var files=Files.newDirectoryStream(directory)) {
      for(Path file:files) {
        String name=file.getFileName().toString();StorageBackup.requireSafePath(file);
        if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&name.matches("turns-[0-9]+\\.tmp")) {
          try(var receipt=connection.prepareStatement("INSERT INTO storage_imports VALUES(?,?,0,?)")) {
            receipt.setString(1,root.relativize(file).toString().replace('\\','/'));receipt.setString(2,StorageBackup.hash(file));
            receipt.setString(3,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest()));receipt.executeUpdate();
          }
          org.slf4j.LoggerFactory.getLogger(SessionTurnMigration.class).warn("Retained inactive legacy Turn temporary file without publishing rows: {}",root.relativize(file));
          continue;
        }
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!name.endsWith(".json"))throw new IOException("Unrecognized legacy Turn source: "+root.relativize(file));
        String key=UUID.fromString(name.substring(0,name.length()-5)).toString();
        if(!name.equalsIgnoreCase(key+".json"))throw new IOException("Noncanonical legacy Turn source name");
        try(var register=connection.prepareStatement("INSERT INTO storage_turn_sources VALUES(?,?,?)")) {
          register.setString(1,scope);register.setString(2,key);register.setString(3,root.relativize(file).toString());register.executeUpdate();
        }
        importFile(connection,root,file,ConversationTurnStore.Turn.class,(row,ordinal)->{
          if(row.runId()==null||row.status()==null)throw new IOException("Turn identity/status is missing");
          SqliteConversationTurnStore.put(connection,scope,key,ordinal,row);
        },"SELECT record FROM turns WHERE project_id=? AND conversation_key=? ORDER BY ordinal",scope,key);
      }
    }
  }
  @FunctionalInterface private interface RowWriter<T>{void write(T row,long ordinal)throws Exception;}
  private static <T>void importFile(Connection connection,Path root,Path source,Class<T> type,RowWriter<T> writer,String verification,String scope,String key)throws Exception {
    StorageBackup.requireSafePath(source);String sourceHash=StorageBackup.hash(source);long count=0;
    var expected=MessageDigest.getInstance("SHA-256");
    try(var input=Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS);var parser=StorageDatabase.JSON.getFactory().createParser(input)) {
      if(parser.nextToken()!=JsonToken.START_ARRAY)throw new IOException("Legacy storage must be a JSON array: "+root.relativize(source));
      JsonToken token;
      while((token=parser.nextToken())!=JsonToken.END_ARRAY) {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Storage import interrupted");
        if(token!=JsonToken.START_OBJECT||count>=1_000_000)throw new IOException("Malformed or excessive legacy rows: "+root.relativize(source));
        T row=StorageDatabase.JSON.readValue(parser,type);String canonical=StorageDatabase.JSON.writeValueAsString(row);
        digestRow(expected,canonical);writer.write(row,count++);
      }
      if(parser.nextToken()!=null)throw new IOException("Trailing legacy JSON content: "+root.relativize(source));
    }
    if(!sourceHash.equals(StorageBackup.hash(source)))throw new IOException("Legacy source changed during import");
    var actual=MessageDigest.getInstance("SHA-256");long stored=0;
    try(var query=connection.prepareStatement(verification)) {
      if(scope!=null){query.setString(1,scope);query.setString(2,key);}
      try(var rows=query.executeQuery()){while(rows.next()){digestRow(actual,rows.getString(1));stored++;}}
    }
    byte[] hash=expected.digest();
    if(stored!=count||!Arrays.equals(hash,actual.digest()))throw new IOException("Legacy import row count/hash mismatch (including duplicate identities): "+root.relativize(source));
    try(var receipt=connection.prepareStatement("INSERT INTO storage_imports VALUES(?,?,?,?)")) {
      receipt.setString(1,root.relativize(source).toString().replace('\\','/'));receipt.setString(2,sourceHash);receipt.setLong(3,count);receipt.setString(4,HexFormat.of().formatHex(hash));receipt.executeUpdate();
    }
  }
  private static void digestRow(MessageDigest digest,String row){digest.update(row.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)'\n');}
}
