package dev.mikoto2000.rei.storage;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.util.*;

/** Metadata only: unknown legacy ownership is never inferred from filenames or mtimes. */
final class ReferenceMigration {
  static void apply(Connection db,Path root)throws Exception {
    try(var sql=db.createStatement()) {
      sql.execute("CREATE TABLE stored_objects(id TEXT PRIMARY KEY,kind TEXT NOT NULL,scope TEXT NOT NULL,conversation_key TEXT,run_id TEXT,relative_path TEXT NOT NULL UNIQUE,size INTEGER NOT NULL,sha256 TEXT NOT NULL,created TEXT,origin_verified INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'AVAILABLE',pinned INTEGER NOT NULL DEFAULT 0,legal_hold INTEGER NOT NULL DEFAULT 0,revision INTEGER NOT NULL DEFAULT 1)");
      sql.execute("CREATE INDEX stored_objects_scope_kind ON stored_objects(scope,kind,created,id)");
      sql.execute("CREATE TABLE object_references(object_id TEXT NOT NULL,kind TEXT NOT NULL,owner TEXT NOT NULL,PRIMARY KEY(object_id,kind,owner))");
      sql.execute("CREATE TABLE retention_policies(id TEXT PRIMARY KEY,kind TEXT NOT NULL,retention_seconds INTEGER,max_bytes INTEGER,max_count INTEGER,automatic INTEGER NOT NULL DEFAULT 0,version INTEGER NOT NULL DEFAULT 1)");
      sql.execute("CREATE TABLE retention_plans(id TEXT PRIMARY KEY,policy_id TEXT NOT NULL,policy_version INTEGER NOT NULL,scope TEXT NOT NULL,snapshot_hash TEXT NOT NULL,record TEXT NOT NULL,created TEXT NOT NULL)");
      sql.execute("CREATE TABLE retention_candidates(plan_id TEXT NOT NULL,object_id TEXT NOT NULL,ordinal INTEGER NOT NULL,record TEXT NOT NULL,PRIMARY KEY(plan_id,object_id),UNIQUE(plan_id,ordinal))");
      sql.execute("CREATE TABLE retention_approvals(plan_id TEXT PRIMARY KEY,snapshot_hash TEXT NOT NULL,created TEXT NOT NULL)");
    }
    try(var insert=db.prepareStatement("INSERT INTO retention_policies(id,kind,retention_seconds) VALUES(?,?,?)")) {
      for(String[] policy:new String[][]{{"raw-results","RAW_RESULT","2592000"},{"activity-raw","ACTIVITY_RAW","7776000"},{"events","EVENT","7776000"},{"reflection","REFLECTION","31536000"},{"sessions","SESSION",null},{"turns","TURN",null},{"conversation-logs","CONVERSATION",null},{"vector-memory","VECTOR",null},{"voice-models","VOICE_MODEL",null},{"worktrees","WORKTREE",null},{"exports","EXPORT",null}}) {
        insert.setString(1,policy[0]);insert.setString(2,policy[1]);if(policy[2]==null)insert.setNull(3,Types.BIGINT);else insert.setLong(3,Long.parseLong(policy[2]));insert.executeUpdate();
      }
    }
    importRaw(db,root,root.resolve("state/context/results"),"");
    Path projects=root.resolve("projects");
    if(Files.exists(projects))try(var entries=Files.newDirectoryStream(projects)) {
      int count=0;for(Path project:entries){if(++count>1000000)throw new IOException("Project limit exceeded");String scope=UUID.fromString(project.getFileName().toString()).toString();importRaw(db,root,project.resolve("state/context/results"),scope);}
    }
    importCheckpoints(db,root);
  }
  private static void importCheckpoints(Connection db,Path root)throws Exception {
    Path source=root.resolve("memory-consolidation.db");StorageBackup.requireSafePath(source);if(Files.notExists(source))return;
    try(var legacy=StorageBackup.readOnly(source);var query=legacy.createStatement()) {
      try(var exists=query.executeQuery("SELECT 1 FROM sqlite_schema WHERE type='table' AND name='checkpoint_revisions'")){if(!exists.next())return;}
      try(var rows=query.executeQuery("SELECT length(CAST(snapshot AS BLOB)),substr(snapshot,1,1048577) FROM checkpoint_revisions")) {
        int count=0;while(rows.next()) {
          if(++count>1000000||rows.getLong(1)>1048576)throw new IOException("Checkpoint reference import limit exceeded; source retained");
          var state=StorageDatabase.JSON.readValue(rows.getString(2),dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.class);
          if(state.schemaVersion()!=1)throw new IOException("Unsupported checkpoint reference schema; source retained");
          StorageObjectRegistry.protectCheckpoint(db,root,state);
        }
      }
    }
  }
  private static void importRaw(Connection db,Path root,Path directory,String scope)throws Exception {
    StorageBackup.requireSafePath(directory);if(Files.notExists(directory))return;
    final long[] count={0};
    Files.walkFileTree(directory,EnumSet.noneOf(FileVisitOption.class),4,new SimpleFileVisitor<>() {
      @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException{StorageBackup.requireSafePath(path);return FileVisitResult.CONTINUE;}
      @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException {
        if(++count[0]>1000000||!attrs.isRegularFile()||attrs.isSymbolicLink())throw new IOException("Unsupported raw result source");
        if(!path.getFileName().toString().endsWith(".json"))return FileVisitResult.CONTINUE;
        String key=path.getParent().getFileName().toString();
        try{UUID.fromString(key);UUID.fromString(path.getFileName().toString().replaceFirst("\\.json$",""));}
        catch(IllegalArgumentException unknown){return FileVisitResult.CONTINUE;}
        try{StorageObjectRegistry.put(db,root,path,"RAW_RESULT",scope,key,null,null,false,List.of());}
        catch(Exception error){throw new IOException("Cannot import raw metadata",error);}
        return FileVisitResult.CONTINUE;
      }
    });
  }
  static void verify(Connection db)throws SQLException {
    try(var sql=db.createStatement()) {
      sql.executeQuery("SELECT id,kind,scope,conversation_key,run_id,relative_path,size,sha256,created,origin_verified,status,pinned,legal_hold,revision FROM stored_objects LIMIT 0").close();
      sql.executeQuery("SELECT object_id,kind,owner FROM object_references LIMIT 0").close();
      sql.executeQuery("SELECT id,kind,retention_seconds,max_bytes,max_count,automatic,version FROM retention_policies LIMIT 0").close();
      sql.executeQuery("SELECT id,policy_id,policy_version,scope,snapshot_hash,record,created FROM retention_plans LIMIT 0").close();
      sql.executeQuery("SELECT plan_id,object_id,ordinal,record FROM retention_candidates LIMIT 0").close();
      sql.executeQuery("SELECT plan_id,snapshot_hash,created FROM retention_approvals LIMIT 0").close();
    }
  }
}
