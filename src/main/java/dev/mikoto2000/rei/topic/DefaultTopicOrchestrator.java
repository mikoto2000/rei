package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 話題の管理に必要な既定話題Orchestratorを扱う。 */
public class DefaultTopicOrchestrator implements TopicOrchestrator {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(DefaultTopicOrchestrator.class);

  /** 話題生成サービス。 */
  private final TopicGeneratorService topicGeneratorService;

  /** 候補保存先。 */
  private volatile dev.mikoto2000.rei.life.LifePolicy life;

  @org.springframework.beans.factory.annotation.Autowired(required = false)
  void setLifeContext(dev.mikoto2000.rei.life.LifePolicy policy) {
    life = policy;
  }

  private boolean lifeAllowed() {
    var policy = life;
    return policy == null || policy.notificationAllowed("LOW", "");
  }

  private boolean lifeValid(dev.mikoto2000.rei.life.LifeContext.Lease lease) {
    var policy = life;
    return policy == null || policy.valid(lease);
  }

  private final TopicCandidateStore candidateStore;

  /** 文脈提供元。 */
  private final TopicGenerationContextProvider contextProvider;

  /** 作業状況追跡。 */
  private final AgentActivityTracker activityTracker;

  /** メッセージ通知先。 */
  private final AgentMessagePublisher messagePublisher;

  /** この機能の動作設定。 */
  private final TopicGeneratorProperties properties;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 実行中。 */
  private final AtomicBoolean running = new AtomicBoolean(false);

  /** 再確認実行先。 */
  private final java.util.concurrent.Executor refreshExecutor;

  /** 再確認形式のバージョン。 */
  private final java.util.concurrent.atomic.AtomicLong refreshVersion =
      new java.util.concurrent.atomic.AtomicLong();

  /** 話題の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DefaultTopicOrchestrator(
      TopicGeneratorService topicGeneratorService,
      TopicCandidateStore candidateStore,
      TopicGenerationContextProvider contextProvider,
      AgentActivityTracker activityTracker,
      AgentMessagePublisher messagePublisher,
      TopicGeneratorProperties properties,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,
      Clock clock) {
    this(
        topicGeneratorService,
        candidateStore,
        contextProvider,
        activityTracker,
        messagePublisher,
        properties,
        eventFactory,
        eventPublisher,
        clock,
        Runnable::run);
  }

  /** 指定された値や利用先を使って、DefaultTopicOrchestratorを初期化する。 */
  public DefaultTopicOrchestrator(
      TopicGeneratorService topicGeneratorService,
      TopicCandidateStore candidateStore,
      TopicGenerationContextProvider contextProvider,
      AgentActivityTracker activityTracker,
      AgentMessagePublisher messagePublisher,
      TopicGeneratorProperties properties,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,
      Clock clock,
      java.util.concurrent.Executor refreshExecutor) {
    this.topicGeneratorService = topicGeneratorService;
    this.candidateStore = candidateStore;
    this.contextProvider = contextProvider;
    this.activityTracker = activityTracker;
    this.messagePublisher = messagePublisher;
    this.properties = properties;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
    this.refreshExecutor = refreshExecutor;
  }

  /** 通知に応じて状態や表示を更新するため、チャット完了を受け付ける。 */
  @Override
  public void onChatCompleted() {
    if (!properties.isEnabled() || !lifeAllowed()) return;
    var owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    long version = refreshVersion.incrementAndGet();
    try {
      refreshExecutor.execute(
          () -> {
            try (var scope = dev.mikoto2000.rei.core.chat.AgentRunScope.open(owner)) {
              if (!lifeAllowed()) return;
              var lease = life == null ? null : life.begin();
              var context = contextProvider.currentContext();
              if (!lifeAllowed() || !lifeValid(lease)) return;
              List<TopicCandidate> candidates =
                  topicGeneratorService.prepareCandidates(
                      context, () -> lifeAllowed() && lifeValid(lease));
              if (!lifeAllowed() || !lifeValid(lease)) return;
              if (refreshVersion.get() != version || Thread.currentThread().isInterrupted()) return;
              candidateStore.replace(candidates, Instant.now(clock));
              eventPublisher.publish(
                  eventFactory.topicCandidatesRefreshed(
                      candidates.size(), candidates.stream().map(TopicCandidate::topic).toList()));
            } catch (RuntimeException failure) {
              log.warn("Topic candidate refresh failed", failure);
            }
          });
    } catch (java.util.concurrent.RejectedExecutionException closing) {
      log.debug("Topic candidate refresh skipped during shutdown");
    }
  }

  /** 通知に応じて状態や表示を更新するため、利用者操作がない状態を受け付ける。 */
  @Override
  public TopicGeneratorService.TopicRunResult onUserIdle() {
    if (!lifeAllowed()) return TopicGeneratorService.TopicRunResult.skipped("life_policy");
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!running.compareAndSet(false, true))
      return TopicGeneratorService.TopicRunResult.skipped("running");
    long activityVersion = activityTracker.activityVersion();
    try {
      Instant now = Instant.now(clock);
      List<TopicCandidate> candidates =
          candidateStore.currentCandidates(now, properties.getCandidateMaxAge());
      TopicExecutionContext executionContext =
          new TopicExecutionContext(
              "topic-" + UUID.randomUUID(),
              now,
              activityTracker.lastUserActivityAt(),
              activityTracker.lastAgentActivityAt(),
              activityTracker.isAgentBusy(),
              TopicTrigger.USER_IDLE);
      TopicGeneratorService.TopicRunResult result =
          topicGeneratorService.runWithCandidates(candidates, executionContext);
      if (!result.spoken() || result.message() == null || result.message().isBlank()) return result;
      if (activityTracker.isAgentBusy() || activityTracker.activityVersion() != activityVersion) {
        eventPublisher.publish(eventFactory.topicAutoSpeakSuppressed("stale activity"));
        return TopicGeneratorService.TopicRunResult.skipped("stale activity");
      }
      if (!lifeAllowed()) {
        eventPublisher.publish(eventFactory.topicAutoSpeakSuppressed("life_policy"));
        return TopicGeneratorService.TopicRunResult.skipped("life_policy");
      }
      Instant publishedAt = Instant.now(clock);
      AgentMessage message =
          new AgentMessage(
              result.messageId(),
              "assistant",
              result.message(),
              MessageOrigin.TOPIC_GENERATOR,
              publishedAt);
      messagePublisher.publish(message);
      topicGeneratorService.completeSpoken(result, publishedAt);
      return result;
    } catch (RuntimeException exception) {
      log.warn("Topic orchestrator failed", exception);
      return TopicGeneratorService.TopicRunResult.skipped("orchestrator failed");
    } finally {
      running.set(false);
    }
  }
}
