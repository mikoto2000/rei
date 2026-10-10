package dev.mikoto2000.rei.storage;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Runs before ordinary Spring beans. The instance holds a process lease until application shutdown. */
public final class StorageMigrationCoordinator implements AutoCloseable {
  public static final int SCHEMA_VERSION=2;
  public enum Stage { COPYING, COPIED, VERIFIED, APPLYING, SCHEMA_WRITTEN, ROWS_IMPORTED, COMPLETE }
  @FunctionalInterface interface Checkpoint { void reached(Stage stage,Path backup)throws Exception; }
  public record Result(int schemaVersion,Path backup) {}
  private final Path root;
  private final FileChannel channel;
  private final FileLock lease;
  private final Checkpoint checkpoint;
  private Result prepared;
  private boolean closed;
  private int sourceVersion;
  public StorageMigrationCoordinator(Path root)throws IOException { this(root,(stage,backup)->{}); }
  StorageMigrationCoordinator(Path supplied,Checkpoint checkpoint)throws IOException {
    root=supplied.toAbsolutePath().normalize();this.checkpoint=checkpoint;StorageBackup.requireSafePath(root);
    Files.createDirectories(root.resolve(".storage"));StorageBackup.requireSafePath(root.resolve(".storage/instance.lock"));
    channel=FileChannel.open(root.resolve(".storage/instance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
    FileLock acquired=null;
    try{acquired=channel.tryLock();if(acquired==null)throw new IOException("Storage is owned by another application: "+root);}
    catch(IOException|OverlappingFileLockException error){channel.close();throw new IOException("Storage is owned by another application: "+root,error);}
    lease=acquired;
  }
  public synchronized Result prepare()throws IOException {
    if(closed)throw new IOException("Storage migration lease is closed");
    if(prepared!=null)return prepared;
    int version=version();
    sourceVersion=version;
    if(version>SCHEMA_VERSION)throw new IOException("Storage schema is newer than this application: "+version);
    if(version==SCHEMA_VERSION){verifyReady();return prepared=new Result(version,null);}
    if(version>0)verifyReady(version);
    Path backup=null;Stage stage=Stage.COPYING;
    try {
      if(StorageBackup.hasSources(root)) {
        backup=root.resolve(".storage/backups").resolve(Instant.now().toEpochMilli()+"-"+UUID.randomUUID());
        journal(stage,backup);checkpoint.reached(stage,backup);StorageBackup.copy(root,backup);
        stage=Stage.COPIED;checkpoint.reached(stage,backup);StorageBackup.verifyCopied(backup);StorageBackup.verifySources(root,backup);
        StorageBackup.markVerified(backup);StorageBackup.verify(backup);
        stage=Stage.VERIFIED;journal(stage,backup);checkpoint.reached(stage,backup);
      }
      stage=Stage.APPLYING;journal(stage,backup);checkpoint.reached(stage,backup);
      if(backup!=null){StorageBackup.verify(backup);StorageBackup.verifySources(root,backup);}
      initialize(backup);
      stage=Stage.COMPLETE;journal(stage,backup);checkpoint.reached(stage,backup);
      return prepared=new Result(SCHEMA_VERSION,backup);
    }catch(Exception error) {
      org.slf4j.LoggerFactory.getLogger(getClass()).error("Storage startup stopped: root={}, stage={}, backup={}. Old source data retained; stop other writers and retry. Failure type: {}",root,stage,backup,error.getClass().getSimpleName());
      if(error instanceof IOException io)throw io;
      throw new IOException("Storage initialization failed at "+stage+"; source retained; backup="+backup,error);
    }
  }
  private int version()throws IOException {
    Path database=root.resolve("storage.db");StorageBackup.requireSafePath(database);
    if(Files.notExists(database,LinkOption.NOFOLLOW_LINKS))return 0;
    try(var db=StorageBackup.readOnly(database);var query=db.createStatement();var rows=query.executeQuery("PRAGMA user_version")){rows.next();return rows.getInt(1);}
    catch(SQLException error){
      if(error instanceof org.sqlite.SQLiteException sqlite&&sqlite.getResultCode()==org.sqlite.SQLiteErrorCode.SQLITE_READONLY_ROLLBACK) {
        recoverOwnedJournal(database);
        try(var db=StorageBackup.readOnly(database);var query=db.createStatement();var rows=query.executeQuery("PRAGMA user_version")){rows.next();return rows.getInt(1);}
        catch(SQLException retry){throw new IOException("Cannot read storage version after journal recovery",retry);}
      }
      throw new IOException("Cannot read storage schema version; normal startup stopped",error);
    }
  }
  /** RW recovery is limited to our interrupted target; legacy databases are never opened RW here. */
  private void recoverOwnedJournal(Path database)throws IOException {
    StorageBackup.requireSafePath(Path.of(database+"-journal"));
    var state=new ObjectMapper().readTree(StorageBackup.metadata(root.resolve(".storage/migration-state.json")));
    int originalVersion=state.path("sourceVersion").asInt(-1);
    if(state.path("targetVersion").asInt(-1)!=SCHEMA_VERSION||originalVersion<0||originalVersion>=SCHEMA_VERSION
        ||!state.path("stage").asText().equals("APPLYING"))throw new IOException("Hot journal is not from a recognized interrupted storage migration; normal startup stopped");
    try(var input=Files.newInputStream(database,LinkOption.NOFOLLOW_LINKS)) {
      byte[] header=input.readNBytes(64);
      // A newly spilled database can still have a sparse header. Fail closed on a
      // newer version field even when the interrupted header is not yet complete.
      if(header.length==64&&java.nio.ByteBuffer.wrap(header).getInt(60)>SCHEMA_VERSION)
        throw new IOException("Interrupted storage header is newer or unsupported by this application");
    }
    String relative=state.path("backup").asText();boolean existed=state.path("databaseExisted").asBoolean(true);
    if(relative.isEmpty()) {
      if(existed||originalVersion!=0)throw new IOException("Interrupted storage database has no verified backup");
    }else {
      Path backup=root.resolve(relative).normalize();
      if(Path.of(relative).isAbsolute()||!backup.startsWith(root.resolve(".storage/backups")))throw new IOException("Unsafe migration recovery backup path");
      StorageBackup.verify(backup);Path original=backup.resolve("files/storage.db");StorageBackup.requireSafePath(original);
      if(Files.exists(original,LinkOption.NOFOLLOW_LINKS)!=existed)throw new IOException("Interrupted storage backup does not match original database identity");
      if(existed)try(var db=StorageBackup.readOnly(original);var query=db.createStatement()) {
        try(var rows=query.executeQuery("PRAGMA user_version")){rows.next();if(rows.getInt(1)!=originalVersion)throw new IOException("Unsupported source version for journal recovery");}
        if(originalVersion==0)try(var rows=query.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'")){rows.next();if(rows.getLong(1)!=0)throw new IOException("Cannot recover an unknown unversioned database");}
        else try(var rows=query.executeQuery("SELECT status FROM storage_migrations WHERE version="+originalVersion)){if(!rows.next()||!"COMPLETE".equals(rows.getString(1)))throw new IOException("Original storage version has no completion marker");}
      }catch(SQLException error){throw new IOException("Cannot validate interrupted migration's original database",error);}
    }
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+database);var query=db.createStatement();var rows=query.executeQuery("PRAGMA user_version")) {
      rows.next();if(rows.getInt(1)>SCHEMA_VERSION)throw new IOException("Recovered storage schema is newer than this application");
    }catch(SQLException error){throw new IOException("Owned SQLite journal recovery failed; source and backup retained",error);}
  }
  private void verifyReady()throws IOException {
    verifyReady(SCHEMA_VERSION);
  }
  private void verifyReady(int version)throws IOException {
    try(var db=StorageBackup.readOnly(root.resolve("storage.db"));var query=db.prepareStatement("SELECT status FROM storage_migrations WHERE version=?")) {
      query.setInt(1,version);try(var rows=query.executeQuery()){if(!rows.next()||!"COMPLETE".equals(rows.getString(1)))throw new IOException("Storage schema has no completed migration marker");}
      if(version>=2)try(var schema=db.createStatement()) {
        schema.executeQuery("SELECT session_id,project_id,created_seconds,created_nanos,updated_sort_seconds,updated_sort_nanos,id_order,record,revision FROM sessions LIMIT 0").close();
        schema.executeQuery("SELECT project_id,conversation_key,ordinal,run_id,run_order,created_seconds,created_nanos,status,source,source_id,record FROM turns LIMIT 0").close();
        schema.executeQuery("SELECT path,source_sha256,records,row_sha256 FROM storage_imports LIMIT 0").close();
        schema.executeQuery("SELECT project_id,conversation_key,path FROM storage_turn_sources LIMIT 0").close();
      }
    }catch(SQLException error){throw new IOException("Storage schema is not a verified completed schema",error);}
  }
  synchronized void verifyCurrentReadyVersion()throws IOException {
    if(closed)throw new IOException("Storage migration lease is closed");
    int current=version();
    if(current>SCHEMA_VERSION)throw new IOException("Storage schema is newer than this application: "+current);
    if(current!=SCHEMA_VERSION)throw new IOException("Storage schema changed while an application lease is active");
    verifyReady();
  }
  private void initialize(Path backup)throws Exception {
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var statement=db.createStatement()) {
      statement.execute("PRAGMA synchronous=FULL");db.setAutoCommit(false);
      // Refuse an unknown target before changing even its persistent journal mode.
      if(sourceVersion==0)try(var rows=statement.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'")) {
        rows.next();if(rows.getLong(1)!=0)throw new IOException("Unknown tables in unversioned storage database");
      }
      db.setAutoCommit(true);
      try(var mode=statement.executeQuery("PRAGMA journal_mode=WAL")){if(!mode.next()||!"wal".equalsIgnoreCase(mode.getString(1)))throw new IOException("Cannot enable concurrent history reads");}
      db.setAutoCommit(false);
      try {
        // A version-zero DB must be empty; never overwrite an unknown application's database.
        if(sourceVersion==0)try(var rows=statement.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name NOT LIKE 'sqlite_%'")) {
          rows.next();if(rows.getLong(1)!=0)throw new IOException("Unknown tables in unversioned storage database");
        }
        if(sourceVersion==0)statement.execute("CREATE TABLE storage_migrations(version INTEGER PRIMARY KEY,status TEXT NOT NULL CHECK(status='COMPLETE'),backup TEXT,completed TEXT NOT NULL)");
        checkpoint.reached(Stage.SCHEMA_WRITTEN,backup);
        SessionTurnMigration.apply(db,root);
        checkpoint.reached(Stage.ROWS_IMPORTED,backup);
        if(backup!=null){StorageBackup.verify(backup);StorageBackup.verifyLegacySources(root,backup);}
        try(var insert=db.prepareStatement("INSERT INTO storage_migrations VALUES(?, 'COMPLETE', ?, ?)")) {
          insert.setString(2,backup==null?null:root.relativize(backup).toString());insert.setString(3,Instant.now().toString());
          if(sourceVersion==0){insert.setInt(1,1);insert.executeUpdate();}
          insert.setInt(1,SCHEMA_VERSION);insert.executeUpdate();
        }
        statement.execute("PRAGMA user_version="+SCHEMA_VERSION);db.commit();
      }catch(Exception error){db.rollback();throw error;}
    }
  }
  private void journal(Stage stage,Path backup)throws IOException {
    StorageBackup.writeAtomic(root.resolve(".storage/migration-state.json"),new ObjectMapper().writeValueAsBytes(Map.of(
        "targetVersion",SCHEMA_VERSION,"sourceVersion",sourceVersion,"databaseExisted",Files.exists(root.resolve("storage.db"),LinkOption.NOFOLLOW_LINKS),
        "stage",stage.name(),"backup",backup==null?"":root.relativize(backup).toString(),"updated",Instant.now().toString())));
  }
  @Override public synchronized void close()throws IOException { if(!closed){closed=true;try{lease.release();}finally{channel.close();}} }
}
