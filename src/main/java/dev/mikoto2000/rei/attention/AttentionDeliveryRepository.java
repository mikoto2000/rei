package dev.mikoto2000.rei.attention;

import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Persistent outbox. A crash after claim is ambiguous and never automatically retried. */
@Repository
public class AttentionDeliveryRepository {
  private static final Set<String> ACTIVE=java.util.concurrent.ConcurrentHashMap.newKeySet();
  public record Lease(Delivery delivery,String key) implements AutoCloseable {public void close(){ACTIVE.remove(key);}}
  private static String processStart(){return ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("unknown");}
  public record Delivery(String id,String projectId,String destination,String cause,String status,int attempts,String reason,Instant updatedAt,String provider,String providerReceipt,Instant retryNotBefore) {
    public Delivery(String id,String projectId,String destination,String cause,String status,int attempts,String reason,Instant updatedAt){this(id,projectId,destination,cause,status,attempts,reason,updatedAt,"WEBHOOK","",Instant.EPOCH);}
  }
  private final JdbcClient db; private final Clock clock;private final String leaseNamespace;
  private final org.springframework.transaction.support.TransactionTemplate tx;
  public AttentionDeliveryRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    try(var connection=source.getConnection()){leaseNamespace=connection.getMetaData().getURL()+":";}catch(java.sql.SQLException failure){throw new IllegalStateException("Outbox storage identity unavailable",failure);}
    db=JdbcClient.create(source);this.clock=clock;tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
    db.sql("CREATE TABLE IF NOT EXISTS attention_delivery(id TEXT PRIMARY KEY,project TEXT NOT NULL,destination TEXT NOT NULL,cause TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,reason TEXT NOT NULL,updated INTEGER NOT NULL)").update();
    db.sql("CREATE UNIQUE INDEX IF NOT EXISTS attention_delivery_inflight ON attention_delivery(status) WHERE status='SENDING'").update();
    var columns=db.sql("PRAGMA table_info(attention_delivery)").query((rs,n)->rs.getString("name")).list();
    if(!columns.contains("provider"))db.sql("ALTER TABLE attention_delivery ADD COLUMN provider TEXT NOT NULL DEFAULT 'WEBHOOK'").update();
    if(!columns.contains("provider_receipt"))db.sql("ALTER TABLE attention_delivery ADD COLUMN provider_receipt TEXT NOT NULL DEFAULT ''").update();
    if(!columns.contains("retry_not_before"))db.sql("ALTER TABLE attention_delivery ADD COLUMN retry_not_before INTEGER NOT NULL DEFAULT 0").update();
    if(!columns.contains("pid"))db.sql("ALTER TABLE attention_delivery ADD COLUMN pid INTEGER").update();
    if(!columns.contains("process_start"))db.sql("ALTER TABLE attention_delivery ADD COLUMN process_start TEXT").update();
    if(!columns.contains("heartbeat"))db.sql("ALTER TABLE attention_delivery ADD COLUMN heartbeat INTEGER").update();
    db.sql("CREATE TABLE IF NOT EXISTS attention_provider_rate(destination TEXT PRIMARY KEY,not_before INTEGER NOT NULL)").update();
  }
  private static final org.springframework.jdbc.core.RowMapper<Delivery> ROW=(rs,n)->new Delivery(rs.getString("id"),rs.getString("project"),rs.getString("destination"),rs.getString("cause"),rs.getString("status"),rs.getInt("attempts"),rs.getString("reason"),Instant.ofEpochMilli(rs.getLong("updated")),rs.getString("provider"),rs.getString("provider_receipt"),Instant.ofEpochMilli(rs.getLong("retry_not_before")));
  public Optional<Delivery> find(String project,String id){return db.sql("SELECT * FROM attention_delivery WHERE project=? AND id=?").params(project,id).query(ROW).optional();}
  public Delivery enqueue(AttentionRepository.Item item,String destination,String cause) {
    return enqueue(item,destination,cause,"WEBHOOK");
  }
  public Delivery enqueue(AttentionRepository.Item item,String destination,String cause,String provider) {
    if(!Set.of("AUTOMATIC","MANUAL").contains(cause))throw new IllegalArgumentException("Invalid delivery cause");
    if(!Set.of("WEBHOOK","SLACK").contains(provider))throw new IllegalArgumentException("Invalid notification provider");
    db.sql("INSERT OR IGNORE INTO attention_delivery(id,project,destination,cause,status,attempts,reason,updated,provider) SELECT ?,?,?,?,'PENDING',0,'queued',?,? WHERE (SELECT COUNT(*) FROM attention_delivery WHERE status IN ('PENDING','SENDING'))<256")
        .params(item.id(),item.projectId(),destination,cause,clock.millis(),provider).update();
    return find(item.projectId(),item.id()).orElseThrow(()->new IllegalArgumentException("Attention delivery queue is full"));
  }
  public List<Delivery> pending(){return db.sql("SELECT * FROM attention_delivery WHERE status='PENDING' ORDER BY updated,id LIMIT 256").query(ROW).list();}
  public boolean claim(String project,String id) {
    return claim(project,id,0);
  }
  public boolean claim(String project,String id,long intervalMillis) {
    return claim(project,id,intervalMillis,null);
  }
  public Lease claimActive(Delivery expected,long intervalMillis){String key=leaseNamespace+expected.id()+":"+(expected.attempts()+1);if(!ACTIVE.add(key))return null;boolean retained=false;try{if(!claim(expected.projectId(),expected.id(),intervalMillis,expected))return null;var delivery=find(expected.projectId(),expected.id()).orElseThrow();retained=true;return new Lease(delivery,key);}finally{if(!retained)ACTIVE.remove(key);}}
  private boolean claim(String project,String id,long intervalMillis,Delivery expected) {
    if(intervalMillis<0||intervalMillis>3600000)throw new IllegalArgumentException("Invalid provider interval");
    return tx.execute(status->{
      // Acquire SQLite's writer lock before reading; a deferred read/write upgrade can fail under parallel claims.
      db.sql("INSERT OR IGNORE INTO attention_provider_rate SELECT destination,0 FROM attention_delivery WHERE project=? AND id=?").params(project,id).update();
      var item=find(project,id).orElseThrow();
      if(expected!=null&&(item.attempts()!=expected.attempts()||!item.destination().equals(expected.destination())||!item.provider().equals(expected.provider())))return false;
      int changed=db.sql("UPDATE attention_delivery SET status='SENDING',attempts=attempts+1,reason='sending',updated=?,pid=?,process_start=?,heartbeat=? WHERE project=? AND id=? AND status='PENDING' AND attempts<3 AND retry_not_before<=? AND NOT EXISTS(SELECT 1 FROM attention_delivery WHERE status='SENDING') AND (SELECT not_before FROM attention_provider_rate WHERE destination=?)<=?")
          .params(clock.millis(),ProcessHandle.current().pid(),processStart(),clock.millis(),project,id,clock.millis(),item.destination(),clock.millis()).update();
      if(changed==1)db.sql("UPDATE attention_provider_rate SET not_before=? WHERE destination=?").params(clock.millis()+intervalMillis,item.destination()).update();
      return changed==1;
    });
  }
  public void finish(Delivery item,String status,String reason) {
    finish(item,new NotificationProvider.Receipt(status,reason,"",0));
  }
  public void finish(Delivery item,NotificationProvider.Receipt receipt) {
    tx.executeWithoutResult(status->{long deadline=clock.millis()+receipt.retryAfterSeconds()*1000L;
      int attempt=item.status().equals("SENDING")?item.attempts():item.attempts()+1;
      int changed=db.sql("UPDATE attention_delivery SET status=?,reason=?,provider_receipt=?,retry_not_before=?,updated=?,heartbeat=? WHERE project=? AND id=? AND status='SENDING' AND attempts=? AND destination=? AND provider=?").params(receipt.status(),receipt.reason(),receipt.providerReceipt(),deadline,clock.millis(),clock.millis(),item.projectId(),item.id(),attempt,item.destination(),item.provider()).update();
      if(changed==1&&receipt.retryAfterSeconds()>0)db.sql("UPDATE attention_provider_rate SET not_before=MAX(not_before,?) WHERE destination=?").params(deadline,item.destination()).update();
    });
  }
  public void skip(Delivery item,String status,String reason) {
    if(!Set.of("BLOCKED","SUPPRESSED").contains(status))throw new IllegalArgumentException("Invalid delivery gate");
    db.sql("UPDATE attention_delivery SET status=?,reason=?,updated=? WHERE project=? AND id=? AND status='PENDING'").params(status,reason,clock.millis(),item.projectId(),item.id()).update();
  }
  public void recover(){record Claimed(String id,int attempt,long pid,String start){}for(var item:db.sql("SELECT id,attempts,pid,process_start FROM attention_delivery WHERE status='SENDING'").query((rs,n)->new Claimed(rs.getString("id"),rs.getInt("attempts"),rs.getLong("pid"),rs.getString("process_start"))).list()){boolean alive=false;try{alive=item.pid()==ProcessHandle.current().pid()?Objects.equals(item.start(),processStart())&&ACTIVE.contains(leaseNamespace+item.id()+":"+item.attempt()):ProcessHandle.of(item.pid()).filter(ProcessHandle::isAlive).flatMap(handle->handle.info().startInstant()).map(start->start.toString().equals(item.start())).orElse(false);}catch(RuntimeException unavailable){alive=false;}if(!alive)db.sql("UPDATE attention_delivery SET status='UNKNOWN',reason='restart_after_claim',updated=? WHERE id=? AND status='SENDING' AND attempts=?").params(clock.millis(),item.id(),item.attempt()).update();}}
  public Delivery retry(String project,String id,String destination,boolean acknowledgeRisk) {
    return retry(project,id,destination,acknowledgeRisk,"WEBHOOK");
  }
  public Delivery retry(String project,String id,String destination,boolean acknowledgeRisk,String provider) {
    if(!Set.of("WEBHOOK","SLACK").contains(provider))throw new IllegalArgumentException("Invalid notification provider");
    var old=find(project,id).orElseThrow(()->new IllegalArgumentException("Delivery not found in this project"));
    if(old.attempts()>0&&!acknowledgeRisk)throw new IllegalArgumentException("A prior attempt may have delivered; explicit duplicate-risk acknowledgement is required");
    int changed=db.sql("UPDATE attention_delivery SET status='PENDING',cause='MANUAL',destination=?,provider=?,reason='manual_retry',updated=? WHERE project=? AND id=? AND status IN ('FAILED','UNKNOWN','BLOCKED') AND attempts=? AND attempts<3 AND (SELECT COUNT(*) FROM attention_delivery WHERE status IN ('PENDING','SENDING'))<256")
        .params(destination,provider,clock.millis(),project,id,old.attempts()).update();
    if(changed!=1)throw new IllegalArgumentException("Delivery cannot be retried in its current state or queue is full");return find(project,id).orElseThrow();
  }
}
