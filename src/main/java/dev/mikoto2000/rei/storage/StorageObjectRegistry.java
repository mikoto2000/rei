package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.core.project.ProjectStorage;

/** Bodies remain in files; metadata and references are committed before publishing a pointer. */
public final class StorageObjectRegistry {
  public record Reference(String kind,String owner) {}
  public record StoredObject(String id,String kind,String scope,String conversationKey,String runId,
      String relativePath,long size,String sha256,Instant created,boolean originVerified,String status,
      boolean pinned,boolean legalHold,long revision) {}
  final Path root;final StorageDatabase database;final Clock clock;
  public StorageObjectRegistry(Path root){this(root,Clock.systemUTC());}
  public StorageObjectRegistry(Path root,Clock clock){this.root=root.toAbsolutePath().normalize();this.database=new StorageDatabase(root);this.clock=clock;}
  public Path root(){return root;}
  public static void registerEvent(Connection db,dev.mikoto2000.rei.event.AgentEvent event,String record)throws Exception {
    String scope=dev.mikoto2000.rei.event.SqliteProjectAgentEventStore.scope(event.projectId());String id="event:"+scope+":"+event.id();byte[] bytes=record.getBytes(StandardCharsets.UTF_8);
    try(var insert=db.prepareStatement("INSERT INTO stored_objects(id,kind,scope,conversation_key,run_id,relative_path,size,sha256,created,origin_verified) VALUES(?,'EVENT',?,?,?,?,?,?,?,1)")) {
      insert.setString(1,id);insert.setString(2,scope);insert.setString(3,event.sessionId()==null?null:key(event.sessionId()));insert.setString(4,event.runId());insert.setString(5,"sqlite/agent_events/"+scope+"/"+event.sequence());insert.setLong(6,bytes.length);insert.setString(7,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));insert.setString(8,event.timestamp().toString());insert.executeUpdate();
    }
  }
  public <T>T serialized(java.util.function.Supplier<T> action){synchronized(database.writer){return action.get();}}
  public void verifyBodyPath(Path path){Path normalized=path.toAbsolutePath().normalize();if(!normalized.startsWith(root))throw new IllegalArgumentException("Object outside storage root");try{StorageBackup.requireSafePath(normalized);}catch(java.io.IOException error){throw new IllegalStateException("Unsafe body path",error);}}
  public void verifyRawReadable(String conversation,String ref){verifyBodyPath(rawPath(conversation,ref));rawObject(conversation,ref).ifPresent(object->{if(!object.status().equals("AVAILABLE"))throw new IllegalStateException("Raw result body is unavailable");});}
  static String scope(String conversation){String project=ProjectStorage.projectId(conversation);return project==null?"":UUID.fromString(project).toString();}
  static String key(String conversation){return UUID.nameUUIDFromBytes(conversation.getBytes(StandardCharsets.UTF_8)).toString();}
  Path rawPath(String conversation,String ref){UUID.fromString(ref);String project=ProjectStorage.projectId(conversation);return (project==null?root:root.resolve("projects").resolve(project)).resolve("state/context/results").resolve(key(conversation)).resolve(ref+".json");}
  static String id(Path root,Path file)throws Exception {
    String path=root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\','/');
    if(System.getProperty("os.name").startsWith("Windows"))path=path.toLowerCase(Locale.ROOT);
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8)));
  }
  static StoredObject put(Connection db,Path root,Path file,String kind,String scope,String conversationKey,String run,Instant created,boolean verified,List<Reference> references)throws Exception {
    file=file.toAbsolutePath().normalize();if(!file.startsWith(root)||file.equals(root))throw new IllegalArgumentException("Object is outside storage root");
    StorageBackup.requireSafePath(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Object body is not a regular file");
    String objectId=id(root,file),relative=root.relativize(file).toString().replace('\\','/');
    try(var insert=db.prepareStatement("INSERT INTO stored_objects(id,kind,scope,conversation_key,run_id,relative_path,size,sha256,created,origin_verified) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO NOTHING")) {
      insert.setString(1,objectId);insert.setString(2,kind);insert.setString(3,scope);insert.setString(4,conversationKey);insert.setString(5,run);insert.setString(6,relative);insert.setLong(7,Files.size(file));insert.setString(8,StorageBackup.hash(file));insert.setString(9,created==null?null:created.toString());insert.setBoolean(10,verified);insert.executeUpdate();
    }
    StoredObject object=find(db,objectId).orElseThrow();
    if(!object.status().equals("AVAILABLE")||!object.kind().equals(kind)||!object.scope().equals(scope)||object.size()!=Files.size(file)||!object.sha256().equals(StorageBackup.hash(file)))throw new IllegalStateException("Object identity/content changed; pointer not published");
    for(Reference reference:references)addReference(db,objectId,reference.kind(),reference.owner());
    return find(db,objectId).orElseThrow();
  }
  public StoredObject registerProducedFile(Path file,String kind,String scope,String run,Instant created,boolean verified,List<Reference> references) {
    if(!kind.equals("ACTIVITY_RAW"))throw new IllegalArgumentException("Use the typed producer for this object kind");
    Path normalized=file.toAbsolutePath().normalize();String physicalScope=scope==null?"":UUID.fromString(scope).toString();
    Path parent=(physicalScope.isEmpty()?root:root.resolve("projects").resolve(physicalScope)).resolve("logs/activity-archives");
    if(!normalized.getParent().equals(parent)||!normalized.getFileName().toString().endsWith(".jsonl"))throw new IllegalArgumentException("Unsupported managed activity path");
    UUID.fromString(normalized.getFileName().toString().replaceFirst("\\.jsonl$",""));
    return database.transaction(db->put(db,root,normalized,kind,physicalScope,null,run,Objects.requireNonNull(created),verified,references));
  }
  public long rawStoredBytes(){return database.read(db->{try(var sql=db.createStatement();var rows=sql.executeQuery("SELECT COALESCE(SUM(size),0) FROM stored_objects WHERE kind='RAW_RESULT' AND status<>'PURGED'")){rows.next();return rows.getLong(1);}});}
  private Path rawBodyPath(String conversation,String hash) {
    if(hash==null||!hash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid raw body hash");
    String project=ProjectStorage.projectId(conversation);
    return (project==null?root:root.resolve("projects").resolve(project)).resolve("state/context/bodies").resolve(key(conversation)).resolve(hash+".json");
  }
  public void verifyRawBodyReadable(String conversation,String hash) {
    var path=rawBodyPath(conversation,hash);verifyBodyPath(path);
    database.read(db->{var object=find(db,id(root,path));if(object.isPresent()&&!object.get().status().equals("AVAILABLE"))throw new IllegalStateException("RAW_RESULT_NOT_FOUND");return null;});
  }
  public StoredObject registerRawWithBody(String conversation,String run,String ref,String hash,boolean pointerCreated,boolean bodyCreated) {
    return database.transaction(db->{
      Path pointer=rawPath(conversation,ref),body=rawBodyPath(conversation,hash);
      if(pointerCreated)StorageBackup.force(pointer);if(bodyCreated)StorageBackup.force(body);
      var parent=put(db,root,pointer,"RAW_RESULT",scope(conversation),key(conversation),run,pointerCreated?clock.instant():null,pointerCreated,List.of());
      put(db,root,body,"RAW_RESULT",scope(conversation),key(conversation),run,bodyCreated?clock.instant():null,bodyCreated,List.of(new Reference("RAW_RESULT_BODY",parent.id())));
      return parent;
    });
  }
  public StoredObject registerRaw(String conversation,String run,String ref,boolean createdByProducer) {
    return database.transaction(db->{StorageBackup.force(rawPath(conversation,ref));return put(db,root,rawPath(conversation,ref),"RAW_RESULT",scope(conversation),key(conversation),run,createdByProducer?clock.instant():null,createdByProducer,List.of());});
  }
  public void protectCheckpoint(dev.mikoto2000.rei.checkpoint.PersistentCheckpoint state){database.transaction(db->{protectCheckpoint(db,root,state);return null;});}
  /** Called only after checkpoint history deletion commits; retained revision references stay intact. */
  public void releaseCheckpointHistory(List<String> owners) {
    database.transaction(db->{
      for(String owner:owners) {
        try(var update=db.prepareStatement("UPDATE stored_objects SET revision=revision+1 WHERE id IN (SELECT object_id FROM object_references WHERE kind='CHECKPOINT' AND owner=?)")) {
          update.setString(1,owner);update.executeUpdate();
        }
        try(var delete=db.prepareStatement("DELETE FROM object_references WHERE kind='CHECKPOINT' AND owner=?")) {
          delete.setString(1,owner);delete.executeUpdate();
        }
      }
      return null;
    });
  }
  static void protectCheckpoint(Connection db,Path root,dev.mikoto2000.rei.checkpoint.PersistentCheckpoint state)throws Exception {
    String owner=state.projectId()+":"+state.taskId()+":"+state.revision();
    String project=ProjectStorage.projectId(state.sessionId());Path base=project==null?root:root.resolve("projects").resolve(project);
    String eventScope;
    try{eventScope=UUID.fromString(state.projectId()).toString();}catch(IllegalArgumentException unknown){eventScope=null;}
    for(var evidence:state.evidence()) {
      if("RAW_RESULT_REFERENCE".equals(evidence.origin())) {
        UUID.fromString(evidence.summary());Path body=base.resolve("state/context/results").resolve(key(state.sessionId())).resolve(evidence.summary()+".json");
        addReference(db,id(root,body),"CHECKPOINT",owner);
      }
      if(evidence.eventId()!=null)addReference(db,eventScope==null?"storage:unknown-checkpoint-event-owner":"event:"+eventScope+":"+evidence.eventId(),"CHECKPOINT",owner);
    }
    for(var operation:state.operations())if(operation.eventId()!=null)addReference(db,eventScope==null?"storage:unknown-checkpoint-event-owner":"event:"+eventScope+":"+operation.eventId(),"CHECKPOINT",owner);
    for(String name:state.files().keySet()) {
      Path file=Path.of(name);
      if(!file.isAbsolute())addReference(db,"storage:unknown-checkpoint-path","CHECKPOINT",owner);
      else if(file.normalize().startsWith(root))addReference(db,id(root,file),"CHECKPOINT",owner);
    }
  }
  public Optional<StoredObject> rawObject(String conversation,String ref){return database.read(db->find(db,id(root,rawPath(conversation,ref))));}
  public List<Reference> references(String id){return database.read(db->references(db,id));}
  static List<Reference> references(Connection db,String id)throws SQLException {
    var result=new ArrayList<Reference>();try(var query=db.prepareStatement("SELECT kind,owner FROM object_references WHERE object_id=? ORDER BY kind,owner LIMIT 10001")){query.setString(1,id);try(var rows=query.executeQuery()){while(rows.next()){if(result.size()>=10000)throw new IllegalStateException("Reference list exceeds display budget; object remains protected");result.add(new Reference(rows.getString(1),rows.getString(2)));}}}return List.copyOf(result);
  }
  public void addReference(String id,String kind,String owner){database.transaction(db->{addReference(db,id,kind,owner);return null;});}
  static void addReference(Connection db,String id,String kind,String owner)throws SQLException {
    if(kind==null||kind.isBlank()||owner==null||owner.isBlank()||kind.length()>128||owner.length()>4096)throw new IllegalArgumentException("Invalid object reference");
    var object=find(db,id);if(object.isPresent()&&!object.get().status().equals("AVAILABLE"))throw new IllegalStateException("Body is unavailable; reference not admitted");
    try(var query=db.prepareStatement("INSERT OR IGNORE INTO object_references VALUES(?,?,?)")){query.setString(1,id);query.setString(2,kind);query.setString(3,owner);if(query.executeUpdate()>0)try(var update=db.prepareStatement("UPDATE stored_objects SET revision=revision+1 WHERE id=?")){update.setString(1,id);update.executeUpdate();}}
  }
  public void pin(String id,boolean value){flag(id,"pinned",value);}
  public void legalHold(String id,boolean value){flag(id,"legal_hold",value);}
  private void flag(String id,String column,boolean value){database.transaction(db->{try(var query=db.prepareStatement("UPDATE stored_objects SET "+column+"=?,revision=revision+1 WHERE id=?")){query.setBoolean(1,value);query.setString(2,id);if(query.executeUpdate()!=1)throw new IllegalArgumentException("Unknown object");}return null;});}
  public Optional<String> protectionReason(StoredObject object){return database.read(db->{var current=find(db,object.id());return current.isEmpty()?Optional.of("unknown-object"):protectionReason(db,current.get());});}
  Optional<String> protectionReason(Connection db,StoredObject object)throws Exception {
    if(!object.originVerified())return Optional.of("unverified-origin");
    if(!object.status().equals("AVAILABLE"))return Optional.of("unavailable");
    return protectionWithoutStatus(db,object);
  }
  Optional<String> protectionWithoutStatus(Connection db,StoredObject object)throws Exception {
    if(!object.originVerified())return Optional.of("unverified-origin");
    if(object.pinned())return Optional.of("pinned");if(object.legalHold())return Optional.of("legal-hold");
    try(var query=db.prepareStatement("SELECT object_id FROM object_references WHERE object_id='storage:unknown-checkpoint-path' OR (?='EVENT' AND object_id='storage:unknown-checkpoint-event-owner') LIMIT 1")){query.setString(1,object.kind());try(var rows=query.executeQuery()){if(rows.next())return Optional.of(rows.getString(1).equals("storage:unknown-checkpoint-path")?"unresolved-checkpoint-path":"unresolved-checkpoint-event-owner");}}
    try(var query=db.prepareStatement("SELECT 1 FROM object_references WHERE object_id=? LIMIT 1")){query.setString(1,object.id());try(var rows=query.executeQuery()){if(rows.next())return Optional.of("referenced");}}
    if(object.kind().equals("RAW_RESULT")) {
      try(var query=db.prepareStatement("SELECT 1 FROM turns WHERE project_id=? AND conversation_key=? AND run_id=? AND status='RUNNING' LIMIT 1")) {
        query.setString(1,object.scope());query.setString(2,object.conversationKey());query.setString(3,object.runId());try(var rows=query.executeQuery()){if(rows.next())return Optional.of("active-run");}
      }
      // Until every persistent reference source has a verified bounded index,
      // absence of a known edge cannot prove this body is disposable.
      return Optional.of("reference-check-incomplete");
    }
    if(object.kind().equals("EVENT"))return Optional.of("reference-check-incomplete");
    if(object.kind().equals("ACTIVITY_RAW")&&object.runId()!=null)return Optional.of("run-history");
    return Optional.empty();
  }
  boolean unchanged(StoredObject object)throws Exception {
    if(object.kind().equals("EVENT"))return database.read(db->{try(var query=db.prepareStatement("SELECT length(CAST(record AS BLOB)),record FROM agent_events WHERE project_id=? AND id=?")){query.setString(1,object.scope());query.setString(2,object.id().substring(("event:"+object.scope()+":").length()));try(var rows=query.executeQuery()){if(!rows.next()||rows.getLong(1)!=object.size()||object.size()>dev.mikoto2000.rei.event.SqliteProjectAgentEventStore.MAX_RECORD_BYTES)return false;return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rows.getString(2).getBytes(StandardCharsets.UTF_8))).equals(object.sha256());}}});
    Path file=root.resolve(object.relativePath()).normalize();if(!file.startsWith(root))return false;StorageBackup.requireSafePath(file);return Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&Files.size(file)==object.size()&&StorageBackup.hash(file).equals(object.sha256());
  }
  static Optional<StoredObject> find(Connection db,String id)throws SQLException {try(var query=db.prepareStatement("SELECT * FROM stored_objects WHERE id=?")){query.setString(1,id);try(var rows=query.executeQuery()){return rows.next()?Optional.of(row(rows)):Optional.empty();}}}
  static StoredObject row(ResultSet rows)throws SQLException {String created=rows.getString("created");return new StoredObject(rows.getString("id"),rows.getString("kind"),rows.getString("scope"),rows.getString("conversation_key"),rows.getString("run_id"),rows.getString("relative_path"),rows.getLong("size"),rows.getString("sha256"),created==null?null:Instant.parse(created),rows.getBoolean("origin_verified"),rows.getString("status"),rows.getBoolean("pinned"),rows.getBoolean("legal_hold"),rows.getLong("revision"));}
}
