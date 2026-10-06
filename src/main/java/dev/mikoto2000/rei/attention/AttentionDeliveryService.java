package dev.mikoto2000.rei.attention;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import jakarta.annotation.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.core.policy.*;

@Component
@EnableConfigurationProperties(AttentionDeliveryProperties.class)
public class AttentionDeliveryService {
  private static final Set<String> KINDS=Set.of("APPROVAL_REQUIRED","POLICY_DENIED","LONG_WAIT","STAGNATION_STOPPED","GOAL_STOPPED","RUN_COMPLETED","RUN_FAILED","DEPENDENCY_COMPLETED","DEPENDENCY_FAILED","DECISION_REQUIRED");
  private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(AttentionDeliveryService.class);
  private final AttentionDeliveryProperties properties; private final AttentionRepository inbox; private final AttentionDeliveryRepository outbox;
  private final AgentEventBus bus; private final ToolPermissionPolicy policy; private final JdkAttentionSender sender;
  private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"attention-delivery");t.setDaemon(true);return t;});
  private final AtomicBoolean busy=new AtomicBoolean(); private volatile boolean closed; private AgentEventBus.Subscription subscription;
  public AttentionDeliveryService(AttentionDeliveryProperties properties,AttentionRepository inbox,AttentionDeliveryRepository outbox,AgentEventBus bus,ToolPermissionPolicy policy,JdkAttentionSender sender) {
    this.properties=properties;this.inbox=inbox;this.outbox=outbox;this.bus=bus;this.policy=policy;this.sender=sender;
  }
  @PostConstruct public synchronized void start(){if(subscription!=null||closed)return;outbox.recover();subscription=bus.subscribe(this::observe);}
  private void observe(AgentEvent event) {
    if(closed||!properties.enabled()||!properties.automatic()||event.type()!=AgentEventType.ATTENTION_REQUIRED||!(event.payload() instanceof AttentionRequiredPayload payload))return;
    try {
      var item=eligible(event.projectId(),payload.attentionId());
      if(!Objects.equals(item.id(),event.correlationId())||!Objects.equals(item.sessionId(),event.sessionId())||!Objects.equals(item.runId(),event.runId())||!item.kind().equals(payload.kind()))return;
      outbox.enqueue(item,destination(),"AUTOMATIC");
    }catch(RuntimeException error){LOG.debug("Attention delivery not queued: {}",error.getClass().getSimpleName());}
  }
  private AttentionRepository.Item eligible(String project,String id) {
    if(!properties.enabled()||!properties.projects().contains(project))throw new IllegalArgumentException("Attention delivery is disabled for this project");
    var item=inbox.get(project,id);
    if(!"OPEN".equals(item.status())||!KINDS.contains(item.kind()))throw new IllegalArgumentException("Attention item is not eligible for delivery");
    UUID.fromString(item.id());return item;
  }
  private String destination(){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(properties.endpoint().getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
  public AttentionDeliveryRepository.Delivery status(String project,String id) {
    var item=inbox.get(project,id);return outbox.find(project,id).orElseGet(()->new AttentionDeliveryRepository.Delivery(item.id(),project,null,null,"NOT_REQUESTED",0,"not_requested",item.createdAt()));
  }
  public AttentionDeliveryRepository.Delivery request(String project,String id){if(closed)throw new IllegalArgumentException("Delivery is closed");return outbox.enqueue(eligible(project,id),destination(),"MANUAL");}
  public AttentionDeliveryRepository.Delivery retry(String project,String id,boolean risk){if(closed)throw new IllegalArgumentException("Delivery is closed");eligible(project,id);return outbox.retry(project,id,destination(),risk);}
  @Scheduled(fixedDelay=5000) public void tick() {
    if(closed||!properties.enabled()||!busy.compareAndSet(false,true))return;
    try{worker.execute(()->{try{dispatchOne();}catch(RuntimeException error){LOG.warn("Attention delivery worker failed: {}",error.getClass().getSimpleName());}finally{busy.set(false);}});}catch(RejectedExecutionException error){busy.set(false);}
  }
  /** Also used by deterministic fixtures; all actual sends pass through the same persisted claim. */
  public synchronized void dispatchOne() {
    if(closed||!properties.enabled())return;
    var pending=outbox.pending();if(pending.isEmpty())return;var item=pending.getFirst();
    AttentionRepository.Item attention;
    try{attention=inbox.get(item.projectId(),item.id());}catch(IllegalArgumentException missing){outbox.skip(item,"SUPPRESSED","inbox_missing");return;}
    if(!"OPEN".equals(attention.status())){outbox.skip(item,"SUPPRESSED","inbox_acknowledged");return;}
    if(!properties.projects().contains(item.projectId())||("AUTOMATIC".equals(item.cause())&&!properties.automatic())||!destination().equals(item.destination())){outbox.skip(item,"BLOCKED","configuration_changed");return;}
    if(policy.evaluate("deliverAttention")!=PermissionDecision.AUTO_APPROVE){outbox.skip(item,"BLOCKED","policy_not_granted");return;}
    String json;
    try {
      eligible(item.projectId(),item.id());
      json=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("schemaVersion",1,"id",attention.id(),"projectId",attention.projectId(),"kind",attention.kind(),"createdAt",attention.createdAt().toString()));
    }catch(Exception error){outbox.skip(item,"BLOCKED","invalid_metadata");return;}
    if(!outbox.claim(item.projectId(),item.id()))return;
    String status="UNKNOWN",reason="transport_outcome_unknown";boolean interrupted=false;
    try{int code=sender.send(properties,item.id(),json);status=code>=200&&code<300?"SENT":"FAILED";reason="http_"+code;}
    catch(InterruptedException error){interrupted=true;reason="interrupted_outcome_unknown";}
    catch(Exception error){reason="transport_outcome_unknown";}
    finally{interrupted|=Thread.interrupted();try{outbox.finish(item,status,reason);}finally{if(interrupted)Thread.currentThread().interrupt();}}
  }
  @PreDestroy public void close(){synchronized(this){if(closed)return;closed=true;if(subscription!=null){subscription.unsubscribe();subscription=null;}}worker.shutdownNow();try{worker.awaitTermination(2,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}}
}
