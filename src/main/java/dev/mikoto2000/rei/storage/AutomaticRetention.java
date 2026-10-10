package dev.mikoto2000.rei.storage;

import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Scoped, prospective consent. Automatic execution quarantines; it never purges. */
public final class AutomaticRetention {
  public record Consent(String id,RetentionPlanner.Policy policy,String scope,int maxObjects,long maxBytes,long intervalSeconds,String snapshotHash,Instant created) {}
  private final StorageObjectRegistry registry;private final RetentionPlanner planner;private final RetentionExecutor executor;
  public AutomaticRetention(StorageObjectRegistry registry,RetentionPlanner planner,RetentionExecutor executor){this.registry=registry;this.planner=planner;this.executor=executor;}
  private static String hash(RetentionPlanner.Policy policy,String scope,int objects,long bytes,long interval)throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(StorageDatabase.JSON.writeValueAsBytes(List.of(policy,scope,objects,bytes,interval))));}
  public Consent propose(String policyId,String supplied,int objects,long bytes,Duration interval) {
    if(objects<1||objects>1000||bytes<1||bytes>1024L*1024*1024||interval==null||interval.toSeconds()<60||interval.toSeconds()>Duration.ofDays(365).toSeconds())throw new IllegalArgumentException("Explicit bounded count, bytes and frequency required");String scope=supplied==null?"":UUID.fromString(supplied).toString();
    return registry.database.transaction(db->{var policy=RetentionPlanner.policy(db,policyId);if(!policy.kind().equals("ACTIVITY_RAW"))throw new IllegalArgumentException("Only the verified Activity collector supports automatic execution");if(policy.retentionSeconds()==null&&policy.maxBytes()==null&&policy.maxCount()==null)throw new IllegalArgumentException("A deletion condition must be specified before requesting consent");
      var consent=new Consent(UUID.randomUUID().toString(),policy,scope,objects,bytes,interval.toSeconds(),hash(policy,scope,objects,bytes,interval.toSeconds()),registry.clock.instant());
      try(var insert=db.prepareStatement("INSERT INTO retention_automatic_consents VALUES(?,?,?,?,?,?,?,?,?,'PROPOSED',?,NULL)")){insert.setString(1,consent.id());insert.setString(2,policyId);insert.setLong(3,policy.version());insert.setString(4,scope);insert.setInt(5,objects);insert.setLong(6,bytes);insert.setLong(7,interval.toSeconds());insert.setString(8,StorageDatabase.JSON.writeValueAsString(consent));insert.setString(9,consent.snapshotHash());insert.setString(10,consent.created().toString());insert.executeUpdate();}return consent;
    });
  }
  private static Consent get(Connection db,String id)throws Exception {UUID.fromString(id);try(var query=db.prepareStatement("SELECT record FROM retention_automatic_consents WHERE id=?")){query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Unknown consent proposal");return StorageDatabase.JSON.readValue(rows.getString(1),Consent.class);}}}
  private static void validate(Connection db,Consent consent)throws Exception {
    var policy=RetentionPlanner.policy(db,consent.policy().id());
    // The automatic flag is operational state; consent binds all deletion conditions.
    var expected=new RetentionPlanner.Policy(policy.id(),policy.kind(),policy.retentionSeconds(),policy.maxBytes(),policy.maxCount(),consent.policy().automatic(),policy.version());
    if(!expected.equals(consent.policy())||!hash(consent.policy(),consent.scope(),consent.maxObjects(),consent.maxBytes(),consent.intervalSeconds()).equals(consent.snapshotHash()))throw new IllegalStateException("Policy or proposal changed; request new explicit consent");
  }
  public void approve(String id){registry.database.transaction(db->{var consent=get(db,id);validate(db,consent);try(var update=db.prepareStatement("UPDATE retention_automatic_consents SET status='ENABLED' WHERE id=? AND status='PROPOSED'")){update.setString(1,id);if(update.executeUpdate()!=1)throw new IllegalStateException("A proposed consent is required");}
    try(var update=db.prepareStatement("UPDATE retention_policies SET automatic=1 WHERE id=?")){update.setString(1,consent.policy().id());update.executeUpdate();}return null;});}
  public void disable(String id){registry.database.transaction(db->{var consent=get(db,id);try(var update=db.prepareStatement("UPDATE retention_automatic_consents SET status='DISABLED' WHERE id=?")){update.setString(1,id);update.executeUpdate();}
    try(var update=db.prepareStatement("UPDATE retention_policies SET automatic=EXISTS(SELECT 1 FROM retention_automatic_consents WHERE policy_id=? AND status='ENABLED' AND policy_version=retention_policies.version) WHERE id=?")){update.setString(1,consent.policy().id());update.setString(2,consent.policy().id());update.executeUpdate();}return null;});}
  public List<Consent> history(){return registry.database.read(db->{var result=new ArrayList<Consent>();try(var query=db.createStatement();var rows=query.executeQuery("SELECT record FROM retention_automatic_consents ORDER BY created DESC,id DESC LIMIT 100")){while(rows.next())result.add(StorageDatabase.JSON.readValue(rows.getString(1),Consent.class));}return List.copyOf(result);});}
  public String state(String id){return registry.database.read(db->{try(var query=db.prepareStatement("SELECT c.status,c.policy_version,p.version,p.automatic FROM retention_automatic_consents c JOIN retention_policies p ON p.id=c.policy_id WHERE c.id=?")){query.setString(1,id);try(var rows=query.executeQuery()){if(!rows.next())throw new IllegalArgumentException("Unknown consent");return rows.getString(1).equals("ENABLED")&&(rows.getLong(2)!=rows.getLong(3)||!rows.getBoolean(4))?"STALE":rows.getString(1);}}});}
  @org.springframework.scheduling.annotation.Scheduled(fixedDelay=60000,initialDelay=60000)
  public void tick(){registry.serialized(()->{
    String id=registry.database.read(db->{try(var query=db.prepareStatement("SELECT c.id FROM retention_automatic_consents c JOIN retention_policies p ON p.id=c.policy_id AND p.version=c.policy_version WHERE c.status='ENABLED' AND p.automatic=1 AND (c.last_run IS NULL OR unixepoch(c.last_run)+c.interval_seconds<=?) ORDER BY COALESCE(c.last_run,''),c.id LIMIT 1")){query.setLong(1,registry.clock.instant().getEpochSecond());try(var rows=query.executeQuery()){return rows.next()?rows.getString(1):null;}}});if(id==null)return null;
    try {
      var consent=registry.database.transaction(db->{var current=get(db,id);validate(db,current);try(var update=db.prepareStatement("UPDATE retention_automatic_consents SET last_run=? WHERE id=?")){update.setString(1,registry.clock.instant().toString());update.setString(2,id);update.executeUpdate();}return current;});
      var plan=planner.plan(consent.policy().id(),consent.scope().isEmpty()?null:consent.scope(),consent.maxObjects(),consent.maxBytes());if(plan.candidates().isEmpty())return null;
      registry.database.transaction(db->{validate(db,consent);try(var insert=db.prepareStatement("INSERT INTO retention_automatic_runs VALUES(?,?,?)")){insert.setString(1,id);insert.setString(2,plan.id());insert.setString(3,registry.clock.instant().toString());insert.executeUpdate();}return null;});planner.approve(plan.id());executor.apply(plan.id());
    }catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(AutomaticRetention.class).warn("Approved retention stopped; inspect consent={} and execution history. Failure type={}",id,error.getClass().getSimpleName());}return null;
  });}
}
