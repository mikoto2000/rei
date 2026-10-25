package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.conversation.ConversationLogStore;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.ConversationIds;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 既定話題Orchestratorの結果と状態の契約を検証するテスト。 */
class DefaultTopicOrchestratorTest {

  @Test
  void guardedRefreshStopsBetweenProvidersWhenWorkBegins() {
    var p = enabledProperties();
    var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var later = new java.util.concurrent.atomic.AtomicInteger();
    TopicCandidateGenerator first =
        context -> {
          allowed.set(false);
          return List.of(candidate());
        };
    TopicCandidateGenerator second =
        context -> {
          later.incrementAndGet();
          return List.of(candidate());
        };
    var service =
        new TopicGeneratorService(
            List.of(first, second),
            new DeterministicTopicRanker(),
            new DefaultSpeakDecisionPolicy(p),
            new TemplateTopicMessageGenerator(),
            new InMemoryCuriosityQueue(),
            p,
            new AgentEventFactory(clock()),
            new InMemoryAgentEventBus(),
            clock());
    assertTrue(service.prepareCandidates(context(), allowed::get).isEmpty());
    assertEquals(0, later.get());
  }

  @Test
  void queuedAutomaticRefreshDoesNotReadContextAfterWorkBegins() {
    var properties = enabledProperties();
    var store = new InMemoryTopicCandidateStore();
    var tasks = new java.util.ArrayDeque<Runnable>();
    var contexts = new java.util.concurrent.atomic.AtomicInteger();
    var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var life = mock(dev.mikoto2000.rei.life.LifePolicy.class);
    org.mockito.Mockito.when(
            life.notificationAllowed(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString()))
        .thenAnswer(i -> allowed.get());
    org.mockito.Mockito.when(life.valid(org.mockito.Mockito.any())).thenReturn(true);
    var orchestrator =
        new DefaultTopicOrchestrator(
            service(properties, new TemplateTopicMessageGenerator()),
            store,
            () -> {
              contexts.incrementAndGet();
              return context();
            },
            new RecordingActivityTracker(now()),
            new RecordingMessagePublisher(),
            properties,
            new AgentEventFactory(clock()),
            new InMemoryAgentEventBus(),
            clock(),
            tasks::add);
    orchestrator.setLifeContext(life);
    orchestrator.onChatCompleted();
    allowed.set(false);
    tasks.remove().run();
    assertEquals(0, contexts.get());
    assertTrue(store.currentCandidates(now(), Duration.ofMinutes(30)).isEmpty());
  }

  @Test
  void workBeginningDuringAutomaticMessagePreparationPreventsPublication() {
    var properties = enabledProperties();
    var store = new InMemoryTopicCandidateStore();
    store.replace(List.of(candidate()), now().minus(Duration.ofMinutes(1)));
    var allowed = new java.util.concurrent.atomic.AtomicBoolean(true);
    var life = mock(dev.mikoto2000.rei.life.LifePolicy.class);
    org.mockito.Mockito.when(
            life.notificationAllowed(
                org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString()))
        .thenAnswer(i -> allowed.get());
    var publisher = new RecordingMessagePublisher();
    TopicMessageGenerator generator =
        (candidate, context) -> {
          allowed.set(false);
          return "prepared before work";
        };
    var orchestrator =
        new DefaultTopicOrchestrator(
            service(properties, generator),
            store,
            this::context,
            new RecordingActivityTracker(now().minus(Duration.ofMinutes(5))),
            publisher,
            properties,
            new AgentEventFactory(clock()),
            new InMemoryAgentEventBus(),
            clock());
    orchestrator.setLifeContext(life);
    var result = orchestrator.onUserIdle();
    assertEquals("life_policy", result.reason());
    assertTrue(publisher.messages.isEmpty());
  }

  /** 話題の管理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void refreshReturnsBeforeGenerationAndDiscardsSupersededResults() {
    var properties = enabledProperties();
    var store = new InMemoryTopicCandidateStore();
    var tasks = new java.util.ArrayDeque<Runnable>();
    var events = new ArrayList<AgentEvent>();
    var bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);
    var contexts = new java.util.concurrent.atomic.AtomicInteger();
    var orchestrator =
        new DefaultTopicOrchestrator(
            service(properties, new TemplateTopicMessageGenerator()),
            store,
            () -> {
              contexts.incrementAndGet();
              return context();
            },
            new RecordingActivityTracker(now()),
            new RecordingMessagePublisher(),
            properties,
            new AgentEventFactory(clock()),
            bus,
            clock(),
            tasks::add);

    orchestrator.onChatCompleted();
    orchestrator.onChatCompleted();
    assertEquals(0, contexts.get());
    assertTrue(store.currentCandidates(now(), Duration.ofMinutes(30)).isEmpty());
    tasks.remove().run();
    assertTrue(store.currentCandidates(now(), Duration.ofMinutes(30)).isEmpty());
    tasks.remove().run();
    assertEquals(List.of(candidate()), store.currentCandidates(now(), Duration.ofMinutes(30)));
    assertEquals(
        1,
        events.stream().filter(e -> e.type() == AgentEventType.TOPIC_CANDIDATES_REFRESHED).count());
  }

  /** 話題の管理で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void chatCompletedRefreshesCandidateStoreWithoutPublishingMessage() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    RecordingMessagePublisher publisher = new RecordingMessagePublisher();
    DefaultTopicOrchestrator orchestrator =
        new DefaultTopicOrchestrator(
            service(properties, new TemplateTopicMessageGenerator()),
            store,
            () -> context(),
            new RecordingActivityTracker(now().minus(Duration.ofMinutes(5))),
            publisher,
            properties,
            new AgentEventFactory(clock()),
            new InMemoryAgentEventBus(),
            clock());

    orchestrator.onChatCompleted();

    assertEquals(List.of(candidate()), store.currentCandidates(now(), Duration.ofMinutes(30)));
    assertTrue(publisher.messages.isEmpty());
  }

  /** 話題の管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void userIdlePublishesAssistantMessageBeforeTopicSpokenEvent() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    store.replace(List.of(candidate()), now().minus(Duration.ofMinutes(1)));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    ConversationLogStore logStore = mock(ConversationLogStore.class);
    RecordingActivityTracker tracker =
        new RecordingActivityTracker(now().minus(Duration.ofMinutes(5)));
    EventAgentMessagePublisher publisher =
        new EventAgentMessagePublisher(
            logStore,
            new AgentEventFactory(clock()),
            bus,
            tracker,
            mock(dev.mikoto2000.rei.ui.shell.sound.AgentMessageNarrator.class));
    DefaultTopicOrchestrator orchestrator =
        new DefaultTopicOrchestrator(
            service(properties, new TemplateTopicMessageGenerator(), bus),
            store,
            () -> context(),
            tracker,
            publisher,
            properties,
            new AgentEventFactory(clock()),
            bus,
            clock());

    TopicGeneratorService.TopicRunResult result = orchestrator.onUserIdle();

    assertTrue(result.spoken());
    verify(logStore).append(ConversationIds.chat(), "assistant", result.message());
    List<AgentEventType> types = events.stream().map(AgentEvent::type).toList();
    assertTrue(
        types.indexOf(AgentEventType.MESSAGE_STARTED) < types.indexOf(AgentEventType.TOPIC_SPOKEN));
    assertEquals(AgentEventType.TOPIC_GENERATION_COMPLETED, types.getLast());
  }

  /** 話題の管理で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void userActivityDuringGenerationPreventsPublishing() {
    TopicGeneratorProperties properties = enabledProperties();
    InMemoryTopicCandidateStore store = new InMemoryTopicCandidateStore();
    store.replace(List.of(candidate()), now().minus(Duration.ofMinutes(1)));
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    List<AgentEvent> events = new ArrayList<>();
    bus.subscribe(events::add);
    RecordingActivityTracker tracker =
        new RecordingActivityTracker(now().minus(Duration.ofMinutes(5)));
    RecordingMessagePublisher publisher = new RecordingMessagePublisher();
    TopicMessageGenerator mutatingGenerator =
        (candidate, context) -> {
          tracker.recordUserActivity(now().plusSeconds(1));
          return "stale message";
        };
    DefaultTopicOrchestrator orchestrator =
        new DefaultTopicOrchestrator(
            service(properties, mutatingGenerator, bus),
            store,
            () -> context(),
            tracker,
            publisher,
            properties,
            new AgentEventFactory(clock()),
            bus,
            clock());

    TopicGeneratorService.TopicRunResult result = orchestrator.onUserIdle();

    assertEquals("stale activity", result.reason());
    assertTrue(publisher.messages.isEmpty());
    assertFalse(events.stream().anyMatch(event -> event.type() == AgentEventType.TOPIC_SPOKEN));
    assertTrue(
        events.stream()
            .anyMatch(event -> event.type() == AgentEventType.TOPIC_AUTO_SPEAK_SUPPRESSED));
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  private TopicGeneratorService service(
      TopicGeneratorProperties properties, TopicMessageGenerator messageGenerator) {
    return service(properties, messageGenerator, new InMemoryAgentEventBus());
  }

  /** 検証で使用するサービスを、指定された条件に合わせて用意する。 */
  private TopicGeneratorService service(
      TopicGeneratorProperties properties,
      TopicMessageGenerator messageGenerator,
      InMemoryAgentEventBus bus) {
    return new TopicGeneratorService(
        List.of(context -> List.of(candidate())),
        new DeterministicTopicRanker(),
        new DefaultSpeakDecisionPolicy(properties),
        messageGenerator,
        new InMemoryCuriosityQueue(),
        properties,
        new AgentEventFactory(clock()),
        bus,
        clock());
  }

  /** {@code enabledProperties} の条件を再現するための補助情報を返す。 */
  private TopicGeneratorProperties enabledProperties() {
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    properties.setMinimumTopicSpeakInterval(Duration.ZERO);
    properties.setCandidateMaxAge(Duration.ofMinutes(30));
    return properties;
  }

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private TopicGenerationContext context() {
    return new TopicGenerationContext(List.of(), List.of(), now(), List.of());
  }

  /** {@code candidate} の条件を再現するための補助情報を返す。 */
  private TopicCandidate candidate() {
    return new TopicCandidate(
        "id",
        "topic",
        "reason",
        TopicType.FOLLOW_UP,
        TopicSource.CONVERSATION,
        1,
        1,
        1,
        0,
        1,
        now());
  }

  /** {@code now} の条件を再現するための補助情報を返す。 */
  private Instant now() {
    return Instant.parse("2026-09-02T00:10:00Z");
  }

  /** {@code clock} の条件を再現するための補助情報を返す。 */
  private Clock clock() {
    return Clock.fixed(now(), ZoneOffset.UTC);
  }

  /** 検証条件を再現するためのRecordingメッセージ通知先。 */
  private static class RecordingMessagePublisher implements AgentMessagePublisher {
    /** 検証に使うメッセージ一覧。 */
    private final List<AgentMessage> messages = new ArrayList<>();

    /** 検証用に、状態や結果を購読先へ通知する。 */
    @Override
    public void publish(AgentMessage message) {
      messages.add(message);
    }
  }

  /** 検証条件を再現するためのRecording作業状況追跡。 */
  private static class RecordingActivityTracker implements AgentActivityTracker {
    /** 検証に使うアプリケーション開始日時。 */
    private final Instant applicationStartedAt;

    /** 検証に使う直近利用者作業状況日時。 */
    private Instant lastUserActivityAt;

    /** 検証に使う直近エージェント作業状況日時。 */
    private Instant lastAgentActivityAt;

    /** 検証に使うエージェント使用中。 */
    private boolean agentBusy;

    /** 検証に使う作業状況形式のバージョン。 */
    private long activityVersion;

    /** 検証条件に必要な値を受け取り、RecordingActivityTrackerを用意する。 */
    RecordingActivityTracker(Instant applicationStartedAt) {
      this.applicationStartedAt = applicationStartedAt;
      this.lastUserActivityAt = applicationStartedAt;
      this.lastAgentActivityAt = applicationStartedAt;
    }

    /** {@code applicationStartedAt} の条件を再現するための補助情報を返す。 */
    @Override
    public Instant applicationStartedAt() {
      return applicationStartedAt;
    }

    /** {@code lastUserActivityAt} の条件を再現するための補助情報を返す。 */
    @Override
    public Instant lastUserActivityAt() {
      return lastUserActivityAt;
    }

    /** {@code lastAgentActivityAt} の条件を再現するための補助情報を返す。 */
    @Override
    public Instant lastAgentActivityAt() {
      return lastAgentActivityAt;
    }

    /** 検証用に、話題の管理において、エージェント使用中かどうかを判定する。 */
    @Override
    public boolean isAgentBusy() {
      return agentBusy;
    }

    /** {@code activityVersion} の条件を再現するための補助情報を返す。 */
    @Override
    public long activityVersion() {
      return activityVersion;
    }

    /** 検証用に、後から経緯や使用量を確認できるよう、利用者作業状況を記録する。 */
    @Override
    public void recordUserActivity(Instant at) {
      lastUserActivityAt = at;
      activityVersion++;
    }

    /** 検証用に、後から経緯や使用量を確認できるよう、エージェント開始を記録する。 */
    @Override
    public void recordAgentStarted(Instant at) {
      lastAgentActivityAt = at;
      agentBusy = true;
      activityVersion++;
    }

    /** 検証用に、後から経緯や使用量を確認できるよう、エージェント完了済みのを記録する。 */
    @Override
    public void recordAgentCompleted(Instant at) {
      lastAgentActivityAt = at;
      agentBusy = false;
      activityVersion++;
    }
  }
}
