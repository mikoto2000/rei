package dev.mikoto2000.rei.attention;

import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.AttentionRequiredPayload;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 注意配信のユースケースを実現する。 */
@Component
@EnableConfigurationProperties(AttentionDeliveryProperties.class)
public class AttentionDeliveryService {
  /** 種類一覧。 */
  private static final Set<String> KINDS =
      Set.of(
          "SECRETARY_DELIVERY",
          "LIFE_DIGEST",
          "APPROVAL_REQUIRED",
          "POLICY_DENIED",
          "LONG_WAIT",
          "STAGNATION_STOPPED",
          "GOAL_STOPPED",
          "RUN_COMPLETED",
          "RUN_FAILED",
          "DEPENDENCY_COMPLETED",
          "DEPENDENCY_FAILED",
          "DECISION_REQUIRED",
          "GITHUB_REVIEW_SUBMITTED",
          "GITHUB_CI_FAILED",
          "GITHUB_PR_MERGED");

  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final org.slf4j.Logger LOG =
      org.slf4j.LoggerFactory.getLogger(AttentionDeliveryService.class);

  /** この機能の動作設定。 */
  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy policy) {
    life = policy;
  }

  private final AttentionDeliveryProperties properties;

  /** 受信した案内や確認待ち情報の保存先。 */
  private final AttentionRepository inbox;

  /** 配信する通知の記録先。 */
  private final AttentionDeliveryRepository outbox;

  /** 通知の連携先。 */
  private final AgentEventBus bus;

  /** 判断基準。 */
  private final ToolPermissionPolicy policy;

  /** 通知の送信先。 */
  private final JdkAttentionSender sender;

  /** 実行の担当。 */
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "attention-delivery");
            t.setDaemon(true);
            return t;
          });

  /** 使用中。 */
  private final AtomicBoolean busy = new AtomicBoolean();

  /** 終了済み。 */
  private volatile boolean closed;

  /** 購読。 */
  private AgentEventBus.Subscription subscription;

  /** Slackとの連携に用いる窓口。 */
  private SlackNotificationProvider slack;

  /** 注意の管理で使用するSlackを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setSlack(SlackNotificationProvider slack) {
    this.slack = slack;
  }

  /** 指定された値や利用先を使って、AttentionDeliveryServiceを初期化する。 */
  public AttentionDeliveryService(
      AttentionDeliveryProperties properties,
      AttentionRepository inbox,
      AttentionDeliveryRepository outbox,
      AgentEventBus bus,
      ToolPermissionPolicy policy,
      JdkAttentionSender sender) {
    this.properties = properties;
    this.inbox = inbox;
    this.outbox = outbox;
    this.bus = bus;
    this.policy = policy;
    this.sender = sender;
  }

  /** 要求された処理を開始する。 */
  @PostConstruct
  public synchronized void start() {
    if (subscription != null || closed) return;
    outbox.recover();
    subscription = bus.subscribe(this::observe);
  }

  /** 状況を判断する根拠を得るため、注意配信サービスを観測する。 */
  private void observe(AgentEvent event) {
    if (closed
        || !properties.enabled()
        || !properties.automatic()
        || event.type() != AgentEventType.ATTENTION_REQUIRED
        || !(event.payload() instanceof AttentionRequiredPayload payload)) return;
    try {
      var item = eligible(event.projectId(), payload.attentionId());
      if (!Objects.equals(item.id(), event.correlationId())
          || !Objects.equals(item.sessionId(), event.sessionId())
          || !Objects.equals(item.runId(), event.runId())
          || !item.kind().equals(payload.kind())) return;
      outbox.enqueue(item, destination(), "AUTOMATIC", provider().name());
    } catch (RuntimeException error) {
      LOG.debug("Attention delivery not queued: {}", error.getClass().getSimpleName());
    }
  }

  /** 注意の管理で用いる対象の条件を返す。 */
  private AttentionRepository.Item eligible(String project, String id) {
    if (!properties.enabled() || !properties.projects().contains(project))
      throw new IllegalArgumentException("Attention delivery is disabled for this project");
    var item = inbox.get(project, id);
    if (!"OPEN".equals(item.status()) || !KINDS.contains(item.kind()))
      throw new IllegalArgumentException("Attention item is not eligible for delivery");
    UUID.fromString(item.id());
    return item;
  }

  /** 注意の管理で用いる提供元を返す。 */
  private NotificationProvider provider() {
    if ("WEBHOOK".equals(properties.provider()))
      return new WebhookNotificationProvider(properties, sender);
    if (slack == null)
      throw new IllegalArgumentException("Slack notification provider is disabled");
    return slack;
  }

  /** 注意の管理で用いる配信先を返す。 */
  private String destination() {
    return provider().destination();
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public AttentionDeliveryRepository.Delivery status(String project, String id) {
    var item = inbox.get(project, id);
    return outbox
        .find(project, id)
        .orElseGet(
            () ->
                new AttentionDeliveryRepository.Delivery(
                    item.id(),
                    project,
                    null,
                    null,
                    "NOT_REQUESTED",
                    0,
                    "not_requested",
                    item.createdAt()));
  }

  /** 注意の管理で用いる要求を返す。 */
  public AttentionDeliveryRepository.Delivery request(String project, String id) {
    if (closed) throw new IllegalArgumentException("Delivery is closed");
    return outbox.enqueue(eligible(project, id), destination(), "MANUAL", provider().name());
  }

  /** 注意の管理で用いる再試行を返す。 */
  public AttentionDeliveryRepository.Delivery retry(String project, String id, boolean risk) {
    if (closed) throw new IllegalArgumentException("Delivery is closed");
    eligible(project, id);
    return outbox.retry(project, id, destination(), risk, provider().name());
  }

  /** 注意の管理に関する定期確認を処理する。 */
  @Scheduled(fixedDelay = 5000)
  public void tick() {
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (closed || !properties.enabled() || !busy.compareAndSet(false, true)) return;
    try {
      worker.execute(
          () -> {
            try {
              dispatchOne();
            } catch (RuntimeException error) {
              LOG.warn("Attention delivery worker failed: {}", error.getClass().getSimpleName());
            } finally {
              busy.set(false);
            }
          });
    } catch (RejectedExecutionException error) {
      busy.set(false);
    }
  }

  /**
   * 注意の管理に関する実行先への引き渡し単一を処理する。
   *
   * <p>Also used by deterministic fixtures; all actual sends pass through the same persisted claim.
   */
  public synchronized void dispatchOne() {
    if (closed || !properties.enabled()) return;
    var pending = outbox.pending();
    if (pending.isEmpty()) return;
    if (life != null && !life.notificationAllowed("NORMAL", pending.getFirst().id())) return;
    var item = pending.getFirst();
    AttentionRepository.Item attention;
    try {
      attention = inbox.get(item.projectId(), item.id());
    } catch (IllegalArgumentException missing) {
      outbox.skip(item, "SUPPRESSED", "inbox_missing");
      return;
    }
    if (!"OPEN".equals(attention.status())) {
      outbox.skip(item, "SUPPRESSED", "inbox_acknowledged");
      return;
    }
    NotificationProvider selected;
    try {
      selected = provider();
    } catch (IllegalArgumentException disabled) {
      outbox.skip(item, "BLOCKED", "provider_disabled");
      return;
    }
    if (!properties.projects().contains(item.projectId())
        || ("AUTOMATIC".equals(item.cause()) && !properties.automatic())
        || !selected.destination().equals(item.destination())
        || !selected.name().equals(item.provider())) {
      outbox.skip(item, "BLOCKED", "configuration_changed");
      return;
    }
    if (policy.evaluate("deliverAttention") != PermissionDecision.AUTO_APPROVE) {
      outbox.skip(item, "BLOCKED", "policy_not_granted");
      return;
    }
    String json;
    try {
      eligible(item.projectId(), item.id());
      json =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "schemaVersion",
                      1,
                      "id",
                      attention.id(),
                      "projectId",
                      attention.projectId(),
                      "kind",
                      attention.kind(),
                      "createdAt",
                      attention.createdAt().toString()));
    } catch (Exception error) {
      outbox.skip(item, "BLOCKED", "invalid_metadata");
      return;
    }
    var lease = outbox.claimActive(item, selected.minimumIntervalMillis());
    if (lease == null) return;
    if (life != null && !life.notificationAllowed("NORMAL", item.id())) {
      try {
        outbox.deferForLifePolicy(lease.delivery());
      } finally {
        lease.close();
      }
      return;
    }
    NotificationProvider.Receipt receipt =
        new NotificationProvider.Receipt("UNKNOWN", "transport_outcome_unknown", "", 0);
    boolean interrupted = false;
    try {
      receipt = selected.deliver(item.id(), json);
    } catch (InterruptedException error) {
      interrupted = true;
      receipt = new NotificationProvider.Receipt("UNKNOWN", "interrupted_outcome_unknown", "", 0);
    } catch (Exception error) {
      receipt = new NotificationProvider.Receipt("UNKNOWN", "transport_outcome_unknown", "", 0);
    } finally {
      interrupted |= Thread.interrupted();
      try {
        outbox.finish(lease.delivery(), receipt);
      } finally {
        lease.close();
        if (interrupted) Thread.currentThread().interrupt();
      }
    }
  }

  /** 注意の管理で利用した資源や登録を解放し、利用を終了する。 */
  @PreDestroy
  public void close() {
    synchronized (this) {
      if (closed) return;
      closed = true;
      if (subscription != null) {
        subscription.unsubscribe();
        subscription = null;
      }
    }
    worker.shutdownNow();
    try {
      worker.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
  }
}
