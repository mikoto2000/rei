package dev.mikoto2000.rei.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.storage.StorageObjectRegistry.StoredObject;

/** Bounded metadata planning and durable approvals. This class never moves or deletes a body. */
public final class RetentionPlanner {
  public record Policy(String id,String kind,Long retentionSeconds,Long maxBytes,Long maxCount,boolean automatic,long version) {}
  public record Plan(String id,String policyId,long policyVersion,String scope,String snapshotHash,
      List<StoredObject> candidates,long recoverableBytes,Map<String,Long> protectedReasons,boolean scanLimited,Instant created) {}
  public record Approval(String planId,String snapshotHash,Instant created) {}
  public record Summary(String id,String policyId,String scope,String snapshotHash,long recoverableBytes,Instant created) {}
  private final StorageObjectRegistry registry;
  public RetentionPlanner(StorageObjectRegistry registry){this.registry=registry;}
  public Policy policy(String id){return registry.database.read(db->policy(db,id));}
  static Policy policy(Connection db,String id)throws SQLException {
    try(var query=db.prepareStatement("SELECT * FROM retention_policies WHERE id=?")){query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Unknown retention policy");return new Policy(id,rows.getString("kind"),nullable(rows,"retention_seconds"),nullable(rows,"max_bytes"),nullable(rows,"max_count"),rows.getBoolean("automatic"),rows.getLong("version"));}}
  }
  private static Long nullable(ResultSet rows,String column)throws SQLException{long value=rows.getLong(column);return rows.wasNull()?null:value;}
  public void updatePolicy(String id,Duration retention,Long maxBytes,Long maxCount) {
    if(retention!=null&&retention.getSeconds()<1||maxBytes!=null&&maxBytes<1||maxCount!=null&&maxCount<1)throw new IllegalArgumentException("Positive retention limits required (at least one second)");
    registry.database.transaction(db->{var old=policy(db,id);if(!Set.of("RAW_RESULT","ACTIVITY_RAW","EVENT","REFLECTION").contains(old.kind())&&(retention!=null||maxBytes!=null||maxCount!=null))throw new IllegalArgumentException("This kind has no automatic retention policy");try(var update=db.prepareStatement("UPDATE retention_policies SET retention_seconds=?,max_bytes=?,max_count=?,version=version+1 WHERE id=?")){update.setObject(1,retention==null?null:retention.getSeconds());update.setObject(2,maxBytes);update.setObject(3,maxCount);update.setString(4,id);update.executeUpdate();}return null;});
  }
  public Plan plan(String policyId,String suppliedScope,int maxObjects,long maxBytes) {
    if(maxObjects<1||maxObjects>1000||maxBytes<1||maxBytes>1024L*1024*1024)throw new IllegalArgumentException("Plan budget must be 1..1000 objects and 1..1GiB");
    String scope=suppliedScope==null?"":UUID.fromString(suppliedScope).toString();
    return registry.database.transaction(db->{
      Policy policy=policy(db,policyId);Instant now=registry.clock.instant();var candidates=new ArrayList<StoredObject>();var protectedReasons=new TreeMap<String,Long>();long bytes=0,totalBytes=0,totalCount=0;boolean limited=false;
      try(var query=db.prepareStatement("SELECT COALESCE(sum(size),0),count(*) FROM stored_objects WHERE kind=? AND scope=? AND status='AVAILABLE'")){query.setString(1,policy.kind());query.setString(2,scope);try(var rows=query.executeQuery()){rows.next();totalBytes=rows.getLong(1);totalCount=rows.getLong(2);}}
      try(var query=db.prepareStatement("SELECT * FROM stored_objects WHERE kind=? AND scope=? ORDER BY created,id LIMIT 1001")) {
        query.setString(1,policy.kind());query.setString(2,scope);try(var rows=query.executeQuery()) {
          int scanned=0;while(rows.next()) {
            if(++scanned>1000){limited=true;break;}StoredObject object=StorageObjectRegistry.row(rows);var protection=registry.protectionReason(db,object);
            if(protection.isPresent()){protectedReasons.merge(protection.get(),1L,Long::sum);continue;}
            if(object.created()==null){protectedReasons.merge("unknown-age",1L,Long::sum);continue;}
            boolean expired=policy.retentionSeconds()!=null&&!object.created().plusSeconds(policy.retentionSeconds()).isAfter(now);
            boolean quota=policy.maxBytes()!=null&&totalBytes-bytes>policy.maxBytes()||policy.maxCount()!=null&&totalCount-candidates.size()>policy.maxCount();
            if(!expired&&!quota)continue;
            if(candidates.size()>=maxObjects||object.size()>maxBytes-bytes){limited=true;continue;}
            if(!registry.unchanged(object)){protectedReasons.merge("changed-or-missing",1L,Long::sum);continue;}
            candidates.add(object);bytes=Math.addExact(bytes,object.size());
          }
        }
      }
      String hash=snapshot(policyId,policy.version(),scope,candidates);Plan plan=new Plan(UUID.randomUUID().toString(),policyId,policy.version(),scope,hash,List.copyOf(candidates),bytes,Map.copyOf(protectedReasons),limited,now);
      var metadata=new Plan(plan.id(),policyId,policy.version(),scope,hash,List.of(),bytes,plan.protectedReasons(),limited,now);
      try(var insert=db.prepareStatement("INSERT INTO retention_plans VALUES(?,?,?,?,?,?,?)")){insert.setString(1,plan.id());insert.setString(2,policyId);insert.setLong(3,policy.version());insert.setString(4,scope);insert.setString(5,hash);insert.setString(6,StorageDatabase.JSON.writeValueAsString(metadata));insert.setString(7,now.toString());insert.executeUpdate();}
      try(var insert=db.prepareStatement("INSERT INTO retention_candidates VALUES(?,?,?,?)")){int ordinal=0;for(var object:candidates){insert.setString(1,plan.id());insert.setString(2,object.id());insert.setInt(3,ordinal++);insert.setString(4,StorageDatabase.JSON.writeValueAsString(object));insert.executeUpdate();}}return plan;
    });
  }
  private static String snapshot(String policy,long version,String scope,List<StoredObject> candidates)throws Exception {
    var digest=MessageDigest.getInstance("SHA-256");digest.update(StorageDatabase.JSON.writeValueAsBytes(List.of(policy,version,scope,candidates)));return HexFormat.of().formatHex(digest.digest());
  }
  public Plan get(String id){return registry.database.read(db->get(db,id));}
  static Plan get(Connection db,String id)throws Exception {
    Plan metadata;try(var query=db.prepareStatement("SELECT record FROM retention_plans WHERE id=?")){query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Unknown plan");metadata=StorageDatabase.JSON.readValue(rows.getString(1),Plan.class);}}
    var candidates=new ArrayList<StoredObject>();try(var query=db.prepareStatement("SELECT record FROM retention_candidates WHERE plan_id=? ORDER BY ordinal LIMIT 1001")){query.setString(1,id);try(var rows=query.executeQuery()){while(rows.next()){if(candidates.size()>=1000)throw new IllegalStateException("Candidate budget exceeded");candidates.add(StorageDatabase.JSON.readValue(rows.getString(1),StoredObject.class));}}}
    return new Plan(metadata.id(),metadata.policyId(),metadata.policyVersion(),metadata.scope(),metadata.snapshotHash(),List.copyOf(candidates),metadata.recoverableBytes(),metadata.protectedReasons(),metadata.scanLimited(),metadata.created());
  }
  void validate(Connection db,Plan plan)throws Exception {
    Policy policy=policy(db,plan.policyId());if(policy.version()!=plan.policyVersion())throw new IllegalStateException("Policy changed; create a new plan");
    if(!snapshot(plan.policyId(),plan.policyVersion(),plan.scope(),plan.candidates()).equals(plan.snapshotHash()))throw new IllegalStateException("Plan snapshot changed");
    for(StoredObject expected:plan.candidates()) {
      StoredObject current=StorageObjectRegistry.find(db,expected.id()).orElseThrow();
      if(!current.equals(expected)||!current.scope().equals(plan.scope())||!current.kind().equals(policy.kind())||registry.protectionReason(db,current).isPresent()||!registry.unchanged(current))throw new IllegalStateException("Candidate changed or became protected; create a new plan");
    }
  }
  public Approval approve(String id){return registry.database.transaction(db->{Plan plan=get(db,id);validate(db,plan);if(plan.candidates().isEmpty())throw new IllegalStateException("An empty plan cannot be approved");Instant now=registry.clock.instant();try(var insert=db.prepareStatement("INSERT INTO retention_approvals VALUES(?,?,?) ON CONFLICT(plan_id) DO NOTHING")){insert.setString(1,id);insert.setString(2,plan.snapshotHash());insert.setString(3,now.toString());insert.executeUpdate();}return new Approval(id,plan.snapshotHash(),now);});}
  public List<Summary> history(){return registry.database.read(db->{var result=new ArrayList<Summary>();try(var query=db.createStatement();var rows=query.executeQuery("SELECT record FROM retention_plans ORDER BY created DESC,id DESC LIMIT 100")){while(rows.next()){var metadata=StorageDatabase.JSON.readValue(rows.getString(1),Plan.class);result.add(new Summary(metadata.id(),metadata.policyId(),metadata.scope(),metadata.snapshotHash(),metadata.recoverableBytes(),metadata.created()));}}return List.copyOf(result);});}
  public record ObjectUsage(String kind,long count,long recordedBodyBytes) {}
  public record Status(List<ObjectUsage> objects,Map<String,Long> protectionReasons,boolean referenceScanLimited) {}
  /** Indexed metadata only; no body hashes, planning, filesystem writes or checkpoints. */
  public Status status(String suppliedScope){String scope=suppliedScope==null?"":UUID.fromString(suppliedScope).toString();return registry.database.read(db->{
    var usages=new ArrayList<ObjectUsage>();try(var query=db.prepareStatement("SELECT kind,count(*),COALESCE(sum(size),0) FROM stored_objects WHERE scope=? GROUP BY kind")){query.setString(1,scope);try(var rows=query.executeQuery()){while(rows.next())usages.add(new ObjectUsage(rows.getString(1),rows.getLong(2),rows.getLong(3)));}}
    var reasons=new TreeMap<String,Long>();boolean limited=false;try(var query=db.prepareStatement("SELECT * FROM stored_objects WHERE scope=? ORDER BY id LIMIT 1001")){query.setString(1,scope);try(var rows=query.executeQuery()){int count=0;while(rows.next()){if(++count>1000){limited=true;break;}registry.protectionReason(db,StorageObjectRegistry.row(rows)).ifPresent(reason->reasons.merge(reason,1L,Long::sum));}}}
    return new Status(List.copyOf(usages),Map.copyOf(reasons),limited);
  });}
}
