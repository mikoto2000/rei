package dev.mikoto2000.rei.github;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.core.project.ProjectContext;
import dev.mikoto2000.rei.event.*;
/** The receipt, facts, inbox and trigger transitions share one database transaction. No execution here. */
public final class GitHubFactRepository {
  public record Receipt(String deliveryId,String status,boolean duplicate,int matched) {}
  public record OwnedFact(String id,String deliveryId,ProjectContext project,String sessionId,GitHubFact fact) {
    public AgentEvent event(Instant receivedAt){return new AgentEvent(id,0,fact.occurredAt().isAfter(receivedAt)?receivedAt:fact.occurredAt(),AgentEventType.valueOf("GITHUB_"+fact.type()),1,sessionId,null,null,fact.sourceId(),null,new GitHubLifecyclePayload(deliveryId,fact.sourceId(),fact),project.id());}
  }
  public record View(String id,String deliveryId,String projectId,String sessionId,String sourceId,GitHubFact fact,Instant receivedAt) {}
  public record Notification(String id,String projectId,String root,String sessionId,String parentId,String kind,String message,Instant createdAt) {
    public AgentEvent event(){return new AgentEvent(UUID.nameUUIDFromBytes(("github-attention:"+id).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),0,createdAt,AgentEventType.ATTENTION_REQUIRED,1,sessionId,null,null,id,parentId,new AttentionRequiredPayload(id,kind,message),projectId);}
  }
  private final JdbcClient db;private final TransactionTemplate tx;private final Clock clock;
  private final ObjectMapper json=new ObjectMapper().registerModule(new JavaTimeModule());
  public GitHubFactRepository(DataSource source,Clock clock) {
    db=JdbcClient.create(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));this.clock=clock;
    db.sql("CREATE TABLE IF NOT EXISTS github_webhook_deliveries(id TEXT PRIMARY KEY,digest TEXT NOT NULL UNIQUE,event TEXT NOT NULL,status TEXT NOT NULL,matched INTEGER NOT NULL,received TEXT NOT NULL)").update();
    db.sql("CREATE TABLE IF NOT EXISTS github_webhook_facts(id TEXT PRIMARY KEY,delivery TEXT NOT NULL,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,source TEXT NOT NULL,type TEXT NOT NULL,repository TEXT NOT NULL,pr INTEGER,fact TEXT NOT NULL,received TEXT NOT NULL)").update();
    db.sql("CREATE INDEX IF NOT EXISTS github_facts_owner ON github_webhook_facts(project,root,session,source,type)").update();
    db.sql("CREATE TABLE IF NOT EXISTS github_notification_outbox(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,parent TEXT NOT NULL,kind TEXT NOT NULL,message TEXT NOT NULL,created TEXT NOT NULL,sent INTEGER NOT NULL DEFAULT 0)").update();
  }
  public Optional<Receipt> duplicate(String delivery,String digest) {
    var sameId=db.sql("SELECT * FROM github_webhook_deliveries WHERE id=?").param(delivery).query((row,n)->{
      if(!row.getString("digest").equals(digest))throw new OperationConflictException();
      return new Receipt(row.getString("id"),row.getString("status"),true,row.getInt("matched"));
    }).optional();
    return sameId.isPresent()?sameId:db.sql("SELECT * FROM github_webhook_deliveries WHERE digest=?").param(digest).query((row,n)->new Receipt(row.getString("id"),row.getString("status"),true,row.getInt("matched"))).optional();
  }
  public void enqueueNotification(OwnedFact source,dev.mikoto2000.rei.attention.AttentionRepository.Item item) {
    db.sql("INSERT OR IGNORE INTO github_notification_outbox(id,project,root,session,parent,kind,message,created) VALUES(?,?,?,?,?,?,?,?)")
        .params(item.id(),source.project().id(),source.project().root().toString(),source.sessionId(),source.id(),item.kind(),item.message(),item.createdAt().toString()).update();
  }
  public List<Notification> pendingNotifications(){return db.sql("SELECT * FROM github_notification_outbox WHERE sent=0 ORDER BY julianday(created),id LIMIT 16")
      .query((row,n)->new Notification(row.getString("id"),row.getString("project"),row.getString("root"),row.getString("session"),row.getString("parent"),row.getString("kind"),row.getString("message"),Instant.parse(row.getString("created")))).list();}
  public void notificationSent(String id){db.sql("UPDATE github_notification_outbox SET sent=1 WHERE id=? AND sent=0").param(id).update();}
  public void notificationSuppressed(String id){db.sql("UPDATE github_notification_outbox SET sent=-1 WHERE id=? AND sent=0").param(id).update();}
  public Receipt accept(String delivery,String digest,String event,List<OwnedFact> facts,int maxDeliveries,int maxFacts,java.util.function.Consumer<OwnedFact> effects) {
    return tx.execute(status->{
      var existing=db.sql("SELECT * FROM github_webhook_deliveries WHERE id=?").param(delivery).query((row,n)-> {
        if(row.getString("id").equals(delivery)&&!row.getString("digest").equals(digest))throw new OperationConflictException();
        return new Receipt(row.getString("id"),row.getString("status"),true,row.getInt("matched"));
      }).optional();
      if(existing.isPresent())return existing.get();
      var replay=db.sql("SELECT * FROM github_webhook_deliveries WHERE digest=?").param(digest).query((row,n)->new Receipt(row.getString("id"),row.getString("status"),true,row.getInt("matched"))).optional();
      if(replay.isPresent())return replay.get();
      if(facts.size()>128||db.sql("SELECT COUNT(*) FROM github_webhook_facts").query(Long.class).single()+facts.size()>maxFacts)throw new CapacityExceeded();
      String state=facts.isEmpty()?"IGNORED":"APPLIED";
      int inserted=db.sql("INSERT INTO github_webhook_deliveries SELECT ?,?,?,?,?,? WHERE (SELECT COUNT(*) FROM github_webhook_deliveries)<?")
          .params(delivery,digest,event,state,facts.size(),clock.instant().toString(),maxDeliveries).update();
      if(inserted!=1)throw new CapacityExceeded();
      for(var owned:facts) {
        var fact=owned.fact();
        db.sql("INSERT INTO github_webhook_facts VALUES(?,?,?,?,?,?,?,?,?,?,?)")
            .params(owned.id(),delivery,owned.project().id(),owned.project().root().toString(),owned.sessionId(),fact.sourceId(),fact.type(),fact.repository(),fact.pullRequest(),encode(fact),clock.instant().toString()).update();
        effects.accept(owned);
      }
      return new Receipt(delivery,state,false,facts.size());
    });
  }
  public View get(ProjectContext owner,String session,String id) {
    return db.sql("SELECT * FROM github_webhook_facts WHERE id=? AND project=? AND root=? AND session=?")
        .params(id,owner.id(),owner.root().toString(),session).query((row,n)->new View(row.getString("id"),row.getString("delivery"),owner.id(),session,row.getString("source"),decode(row.getString("fact")),Instant.parse(row.getString("received"))))
        .optional().orElseThrow(()->new ResourceNotFoundException("GitHub fact"));
  }
  public List<View> forDelivery(ProjectContext owner,String session,String delivery) {
    var views=db.sql("SELECT * FROM github_webhook_facts WHERE delivery=? AND project=? AND root=? AND session=? ORDER BY id LIMIT 128")
        .params(delivery,owner.id(),owner.root().toString(),session).query((row,n)->new View(row.getString("id"),delivery,owner.id(),session,row.getString("source"),decode(row.getString("fact")),Instant.parse(row.getString("received")))).list();
    if(views.isEmpty())throw new ResourceNotFoundException("GitHub facts");return views;
  }
  public boolean merged(String project,String root,String session,String repository,int pr) {
    return db.sql("SELECT COUNT(*) FROM github_webhook_facts WHERE project=? AND root=? AND session=? AND repository=? AND pr=? AND type='PR_MERGED'")
        .params(project,root,session,repository.toLowerCase(Locale.ROOT),pr).query(Long.class).single()>0;
  }
  private String encode(GitHubFact fact){try{return json.writeValueAsString(fact);}catch(java.io.IOException invalid){throw new IllegalStateException("GitHub fact encoding failed",invalid);}}
  private GitHubFact decode(String fact){try{return json.readValue(fact,GitHubFact.class);}catch(java.io.IOException invalid){throw new IllegalStateException("GitHub fact decoding failed",invalid);}}
  public static final class CapacityExceeded extends RuntimeException {public CapacityExceeded(){super("GitHub receipt capacity reached");}}
}
