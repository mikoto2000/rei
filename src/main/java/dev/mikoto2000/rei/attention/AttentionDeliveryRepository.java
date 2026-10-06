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
  public record Delivery(String id,String projectId,String destination,String cause,String status,int attempts,String reason,Instant updatedAt) {}
  private final JdbcClient db; private final Clock clock;
  public AttentionDeliveryRepository(@Qualifier("memoryConsolidationDataSource") DataSource source,Clock clock) {
    db=JdbcClient.create(source);this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS attention_delivery(id TEXT PRIMARY KEY,project TEXT NOT NULL,destination TEXT NOT NULL,cause TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,reason TEXT NOT NULL,updated INTEGER NOT NULL)").update();
    db.sql("CREATE UNIQUE INDEX IF NOT EXISTS attention_delivery_inflight ON attention_delivery(status) WHERE status='SENDING'").update();
  }
  private static final org.springframework.jdbc.core.RowMapper<Delivery> ROW=(rs,n)->new Delivery(rs.getString("id"),rs.getString("project"),rs.getString("destination"),rs.getString("cause"),rs.getString("status"),rs.getInt("attempts"),rs.getString("reason"),Instant.ofEpochMilli(rs.getLong("updated")));
  public Optional<Delivery> find(String project,String id){return db.sql("SELECT * FROM attention_delivery WHERE project=? AND id=?").params(project,id).query(ROW).optional();}
  public Delivery enqueue(AttentionRepository.Item item,String destination,String cause) {
    if(!Set.of("AUTOMATIC","MANUAL").contains(cause))throw new IllegalArgumentException("Invalid delivery cause");
    db.sql("INSERT OR IGNORE INTO attention_delivery SELECT ?,?,?,?,'PENDING',0,'queued',? WHERE (SELECT COUNT(*) FROM attention_delivery WHERE status IN ('PENDING','SENDING'))<256")
        .params(item.id(),item.projectId(),destination,cause,clock.millis()).update();
    return find(item.projectId(),item.id()).orElseThrow(()->new IllegalArgumentException("Attention delivery queue is full"));
  }
  public List<Delivery> pending(){return db.sql("SELECT * FROM attention_delivery WHERE status='PENDING' ORDER BY updated,id LIMIT 256").query(ROW).list();}
  public boolean claim(String project,String id) {
    return db.sql("UPDATE attention_delivery SET status='SENDING',attempts=attempts+1,reason='sending',updated=? WHERE project=? AND id=? AND status='PENDING' AND attempts<3 AND NOT EXISTS(SELECT 1 FROM attention_delivery WHERE status='SENDING')")
        .params(clock.millis(),project,id).update()==1;
  }
  public void finish(Delivery item,String status,String reason) {
    if(!Set.of("SENT","FAILED","UNKNOWN").contains(status))throw new IllegalArgumentException("Invalid delivery outcome");
    db.sql("UPDATE attention_delivery SET status=?,reason=?,updated=? WHERE project=? AND id=? AND status='SENDING'").params(status,reason,clock.millis(),item.projectId(),item.id()).update();
  }
  public void skip(Delivery item,String status,String reason) {
    if(!Set.of("BLOCKED","SUPPRESSED").contains(status))throw new IllegalArgumentException("Invalid delivery gate");
    db.sql("UPDATE attention_delivery SET status=?,reason=?,updated=? WHERE project=? AND id=? AND status='PENDING'").params(status,reason,clock.millis(),item.projectId(),item.id()).update();
  }
  public void recover(){db.sql("UPDATE attention_delivery SET status='UNKNOWN',reason='restart_after_claim',updated=? WHERE status='SENDING'").param(clock.millis()).update();}
  public Delivery retry(String project,String id,String destination,boolean acknowledgeRisk) {
    var old=find(project,id).orElseThrow(()->new IllegalArgumentException("Delivery not found in this project"));
    if(old.attempts()>0&&!acknowledgeRisk)throw new IllegalArgumentException("A prior attempt may have delivered; explicit duplicate-risk acknowledgement is required");
    int changed=db.sql("UPDATE attention_delivery SET status='PENDING',cause='MANUAL',destination=?,reason='manual_retry',updated=? WHERE project=? AND id=? AND status IN ('FAILED','UNKNOWN','BLOCKED') AND attempts=? AND attempts<3 AND (SELECT COUNT(*) FROM attention_delivery WHERE status IN ('PENDING','SENDING'))<256")
        .params(destination,clock.millis(),project,id,old.attempts()).update();
    if(changed!=1)throw new IllegalArgumentException("Delivery cannot be retried in its current state or queue is full");return find(project,id).orElseThrow();
  }
}
