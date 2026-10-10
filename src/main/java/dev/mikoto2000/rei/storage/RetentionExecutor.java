package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.security.MessageDigest;
import java.util.*;
import dev.mikoto2000.rei.storage.StorageObjectRegistry.StoredObject;

/** Durable, bounded quarantine. Unproven collectors can never reach file mutation. */
public final class RetentionExecutor {
  public static final Duration PURGE_GRACE=Duration.ofDays(7);
  public record Execution(String id,String planId,String snapshotHash,String status,Instant created,Instant updated) {}
  private record Item(String objectId,int ordinal,String state,String quarantinePath,Instant quarantined,StoredObject original) {}
  @FunctionalInterface interface Checkpoint {void reached(String stage,String execution)throws Exception;}
  private final StorageObjectRegistry registry;private final RetentionPlanner planner;private final Checkpoint checkpoint;
  public RetentionExecutor(StorageObjectRegistry registry,RetentionPlanner planner){this(registry,planner,(stage,id)->{});}
  RetentionExecutor(StorageObjectRegistry registry,RetentionPlanner planner,Checkpoint checkpoint){this.registry=registry;this.planner=planner;this.checkpoint=checkpoint;}
  public Execution get(String id){return registry.database.read(db->get(db,id));}
  private static Execution get(Connection db,String id)throws Exception {
    UUID.fromString(id);try(var query=db.prepareStatement("SELECT * FROM retention_executions WHERE id=?")){query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Unknown execution");return new Execution(id,rows.getString("plan_id"),rows.getString("snapshot_hash"),rows.getString("status"),Instant.parse(rows.getString("created")),Instant.parse(rows.getString("updated")));}}
  }
  public List<Execution> history(){return registry.database.read(db->{var result=new ArrayList<Execution>();try(var query=db.createStatement();var rows=query.executeQuery("SELECT id FROM retention_executions ORDER BY created DESC,id DESC LIMIT 100")){while(rows.next())result.add(get(db,rows.getString(1)));}return List.copyOf(result);});}
  private static List<Item> items(Connection db,String id)throws Exception {
    var result=new ArrayList<Item>();try(var query=db.prepareStatement("SELECT * FROM retention_execution_items WHERE execution_id=? ORDER BY ordinal LIMIT 1001")){query.setString(1,id);try(var rows=query.executeQuery()){while(rows.next()){if(result.size()>=1000)throw new IllegalStateException("Execution item budget exceeded");String time=rows.getString("quarantined");result.add(new Item(rows.getString("object_id"),rows.getInt("ordinal"),rows.getString("state"),rows.getString("quarantine_path"),time==null?null:Instant.parse(time),StorageDatabase.JSON.readValue(rows.getString("record"),StoredObject.class)));}}}return List.copyOf(result);
  }
  private Path original(StoredObject object)throws Exception {
    if(!object.kind().equals("ACTIVITY_RAW"))throw new IllegalStateException("This collector has no complete reference proof");
    Path path=registry.root.resolve(object.relativePath()).normalize();Path parent=(object.scope().isEmpty()?registry.root:registry.root.resolve("projects").resolve(UUID.fromString(object.scope()).toString())).resolve("logs/activity-archives");
    if(!path.getParent().equals(parent)||!path.getFileName().toString().endsWith(".jsonl")||!StorageObjectRegistry.id(registry.root,path).equals(object.id()))throw new IllegalStateException("Unsupported producer path");
    UUID.fromString(path.getFileName().toString().replaceFirst("\\.jsonl$",""));StorageBackup.requireSafePath(path);return path;
  }
  private Path quarantine(String execution,Item item)throws Exception {
    Path expected=registry.root.resolve(".storage/quarantine").resolve(UUID.fromString(execution).toString()).resolve(item.ordinal()+".body");
    if(!registry.root.resolve(item.quarantinePath()).normalize().equals(expected))throw new IllegalStateException("Invalid quarantine identity");StorageBackup.requireSafePath(expected);return expected;
  }
  private static void verify(Path file,StoredObject object)throws Exception {
    StorageBackup.requireSafePath(file);if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=object.size()||!StorageBackup.hash(file).equals(object.sha256()))throw new IllegalStateException("Body changed or missing; retained for inspection");
  }
  private void protect(Connection db,Item item)throws Exception {
    var current=StorageObjectRegistry.find(db,item.objectId()).orElseThrow();
    if(!current.relativePath().equals(item.original().relativePath())||current.size()!=item.original().size()||!current.sha256().equals(item.original().sha256())||registry.protectionWithoutStatus(db,current).isPresent())throw new IllegalStateException("Body changed or became protected; no mutation admitted");
  }
  private static void move(Path source,Path target)throws Exception {
    StorageBackup.requireSafePath(source);StorageBackup.requireSafePath(target);Files.createDirectories(target.getParent());
    // No REPLACE_EXISTING: the destination must remain absent, including on retry.
    Files.move(source,target);
    StorageBackup.force(target);
  }
  private void executionState(Connection db,String id,String state)throws Exception {
    try(var query=db.prepareStatement("UPDATE retention_executions SET status=?,updated=? WHERE id=?")){query.setString(1,state);query.setString(2,registry.clock.instant().toString());query.setString(3,id);query.executeUpdate();}
  }
  private void itemState(Connection db,String id,Item item,String state,String objectStatus,Instant quarantined)throws Exception {
    try(var query=db.prepareStatement("UPDATE retention_execution_items SET state=?,quarantined=COALESCE(?,quarantined) WHERE execution_id=? AND object_id=?")){query.setString(1,state);query.setString(2,quarantined==null?null:quarantined.toString());query.setString(3,id);query.setString(4,item.objectId());query.executeUpdate();}
    try(var query=db.prepareStatement("UPDATE stored_objects SET status=?,revision=revision+1 WHERE id=?")){query.setString(1,objectStatus);query.setString(2,item.objectId());if(query.executeUpdate()!=1)throw new IllegalStateException("Missing object metadata");}
  }
  public Execution apply(String planId){return registry.serialized(()->{
    var execution=registry.database.transaction(db->{
      try(var query=db.prepareStatement("SELECT id FROM retention_executions WHERE plan_id=?")){query.setString(1,planId);try(var rows=query.executeQuery()){if(rows.next())return get(db,rows.getString(1));}}
      var plan=RetentionPlanner.get(db,planId);planner.validate(db,plan);
      try(var query=db.prepareStatement("SELECT snapshot_hash FROM retention_approvals WHERE plan_id=?")){query.setString(1,planId);try(var rows=query.executeQuery()){if(!rows.next()||!rows.getString(1).equals(plan.snapshotHash())||plan.candidates().isEmpty())throw new IllegalStateException("Exact nonempty plan approval required");}}
      String id=UUID.randomUUID().toString(),now=registry.clock.instant().toString();
      for(var object:plan.candidates())original(object);
      try(var insert=db.prepareStatement("INSERT INTO retention_executions VALUES(?,?,?,'DELETING',?,?)")){insert.setString(1,id);insert.setString(2,planId);insert.setString(3,plan.snapshotHash());insert.setString(4,now);insert.setString(5,now);insert.executeUpdate();}
      try(var insert=db.prepareStatement("INSERT INTO retention_execution_items VALUES(?,?,?,'DELETING',?,NULL,?)")){int ordinal=0;for(var object:plan.candidates()){
        insert.setString(1,id);insert.setString(2,object.id());insert.setInt(3,ordinal);insert.setString(4,".storage/quarantine/"+id+"/"+ordinal+".body");insert.setString(5,StorageDatabase.JSON.writeValueAsString(object));insert.executeUpdate();ordinal++;
        try(var update=db.prepareStatement("UPDATE stored_objects SET status='DELETING',revision=revision+1 WHERE id=?")){update.setString(1,object.id());update.executeUpdate();}
      }}return get(db,id);
    });
    if(execution.status().equals("QUARANTINED"))return execution;
    if(!execution.status().equals("DELETING"))throw new IllegalStateException("Execution cannot be reapplied in this state");
    registry.database.read(db->{var plan=RetentionPlanner.get(db,execution.planId());if(!plan.snapshotHash().equals(execution.snapshotHash())||RetentionPlanner.policy(db,plan.policyId()).version()!=plan.policyVersion())throw new IllegalStateException("Policy changed; restore this interrupted execution instead of applying it");return null;});
    try {
      checkpoint.reached("DELETING",execution.id());
      for(var item:registry.database.read(db->items(db,execution.id()))) {
        if(item.state().equals("QUARANTINED"))continue;
        registry.database.read(db->{protect(db,item);return null;});Path source=original(item.original()),target=quarantine(execution.id(),item);
        boolean sourceExists=Files.exists(source,LinkOption.NOFOLLOW_LINKS),targetExists=Files.exists(target,LinkOption.NOFOLLOW_LINKS);
        if(sourceExists&&targetExists||!sourceExists&&!targetExists)throw new IllegalStateException("Ambiguous quarantine state; both paths retained");
        if(sourceExists){verify(source,item.original());move(source,target);}verify(target,item.original());
        checkpoint.reached("MOVED",execution.id());
        registry.database.transaction(db->{protect(db,item);itemState(db,execution.id(),item,"QUARANTINED","QUARANTINED",registry.clock.instant());return null;});
      }
      registry.database.transaction(db->{executionState(db,execution.id(),"QUARANTINED");return null;});return get(execution.id());
    }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("Quarantine interrupted; retry apply or restore this execution",error);}
  });}
  public Execution restore(String id){return registry.serialized(()->{
    var execution=get(id);if(execution.status().equals("RESTORED"))return execution;
    if(!Set.of("DELETING","QUARANTINED","RESTORING").contains(execution.status()))throw new IllegalStateException("Execution cannot be restored in this state");
    try {
      var entries=registry.database.read(db->items(db,id));
      // Preflight every entry before making a restoration request durable.
      for(var item:entries) {
        if(item.state().equals("RESTORED"))continue;Path source=original(item.original()),target=quarantine(id,item);
        boolean atSource=Files.exists(source,LinkOption.NOFOLLOW_LINKS),atTarget=Files.exists(target,LinkOption.NOFOLLOW_LINKS);
        if(atSource&&atTarget||!atSource&&!atTarget)throw new IllegalStateException("Restore collision or missing body; no overwrite");
        if(atSource&&!Set.of("DELETING","RESTORING").contains(item.state()))throw new IllegalStateException("Original path already exists; no overwrite");verify(atSource?source:target,item.original());
      }
      registry.database.transaction(db->{executionState(db,id,"RESTORING");try(var update=db.prepareStatement("UPDATE retention_execution_items SET state='RESTORING' WHERE execution_id=? AND state<>'RESTORED'")){update.setString(1,id);update.executeUpdate();}return null;});
      for(var item:registry.database.read(db->items(db,id))) {
        if(item.state().equals("RESTORED"))continue;Path source=original(item.original()),target=quarantine(id,item);
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verify(target,item.original());move(target,source);}verify(source,item.original());
        checkpoint.reached("RESTORED_FILE",id);registry.database.transaction(db->{itemState(db,id,item,"RESTORED","AVAILABLE",null);return null;});
      }
      registry.database.transaction(db->{executionState(db,id,"RESTORED");return null;});return get(id);
    }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("Restore interrupted; retry this execution",error);}
  });}
  private String purgeSnapshot(Connection db,String id,boolean verifyBodies)throws Exception {
    var digest=MessageDigest.getInstance("SHA-256");digest.update(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    var plan=RetentionPlanner.get(db,get(db,id).planId());var policy=RetentionPlanner.policy(db,plan.policyId());
    digest.update(StorageDatabase.JSON.writeValueAsBytes(new RetentionPlanner.Policy(policy.id(),policy.kind(),policy.retentionSeconds(),policy.maxBytes(),policy.maxCount(),false,policy.version())));
    for(var item:items(db,id)) {
      if(!Set.of("QUARANTINED","PURGING").contains(item.state())||item.quarantined()==null||item.quarantined().plus(PURGE_GRACE).isAfter(registry.clock.instant()))throw new IllegalStateException("Every item must complete the seven-day quarantine grace");
      protect(db,item);if(Files.exists(original(item.original()),LinkOption.NOFOLLOW_LINKS))throw new IllegalStateException("Original path reappeared; no purge admitted");
      Path file=quarantine(id,item);if(verifyBodies||Files.exists(file,LinkOption.NOFOLLOW_LINKS))verify(file,item.original());
      var current=StorageObjectRegistry.find(db,item.objectId()).orElseThrow();
      // State changes during purge are excluded; identity, flags and revisions are bound.
      digest.update(StorageDatabase.JSON.writeValueAsBytes(List.of(item.objectId(),item.quarantinePath(),item.quarantined(),current.revision(),current.pinned(),current.legalHold(),current.sha256(),current.size())));
    }return HexFormat.of().formatHex(digest.digest());
  }
  public void approvePurge(String id){registry.serialized(()->registry.database.transaction(db->{String state=get(db,id).status();if(!Set.of("QUARANTINED","PURGING").contains(state))throw new IllegalStateException("Only quarantined or previously approved interrupted purges can receive approval");
    if(state.equals("PURGING"))try(var query=db.prepareStatement("SELECT 1 FROM retention_purge_approvals WHERE execution_id=?")){query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalStateException("Interrupted purge has no prior authorization");}}
    String hash=purgeSnapshot(db,id,state.equals("QUARANTINED"));
    try(var insert=db.prepareStatement("INSERT INTO retention_purge_approvals VALUES(?,?,?) ON CONFLICT(execution_id) DO UPDATE SET snapshot_hash=excluded.snapshot_hash,created=excluded.created")){insert.setString(1,id);insert.setString(2,hash);insert.setString(3,registry.clock.instant().toString());insert.executeUpdate();}return null;}));}
  public Execution purge(String id){return registry.serialized(()->{
    var execution=get(id);if(execution.status().equals("DELETED"))return execution;
    if(!Set.of("QUARANTINED","PURGING").contains(execution.status()))throw new IllegalStateException("Execution cannot be purged in this state");
    registry.database.transaction(db->{String hash=purgeSnapshot(db,id,!execution.status().equals("PURGING"));
      try(var query=db.prepareStatement("SELECT snapshot_hash FROM retention_purge_approvals WHERE execution_id=?")){query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next()||!rows.getString(1).equals(hash))throw new IllegalStateException("Separate, unchanged quarantine purge approval required");}}
      executionState(db,id,"PURGING");try(var update=db.prepareStatement("UPDATE retention_execution_items SET state='PURGING' WHERE execution_id=?")){update.setString(1,id);update.executeUpdate();}return null;
    });
    try {
      checkpoint.reached("PURGING",id);
      // Physical removals occur only after the whole approved batch is durable.
      for(var item:registry.database.read(db->items(db,id))) {
        registry.database.read(db->{protect(db,item);return null;});Path path=quarantine(id,item);
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)){verify(path,item.original());Files.delete(path);}checkpoint.reached("PURGED_FILE",id);
      }
      registry.database.transaction(db->{for(var item:items(db,id))itemState(db,id,item,"DELETED","DELETED",null);executionState(db,id,"DELETED");return null;});return get(id);
    }catch(RuntimeException error){throw error;}catch(Exception error){throw new IllegalStateException("Purge interrupted; retry the approved execution",error);}
  });}
}
