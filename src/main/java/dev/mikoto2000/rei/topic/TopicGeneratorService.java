package dev.mikoto2000.rei.topic;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 話題生成のユースケースを実現する。 */
public class TopicGeneratorService {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(TopicGeneratorService.class);

  /** 生成一覧。 */
  private final List<TopicCandidateGenerator> generators;

  /** 話題の優先順位を評価する窓口。 */
  private final TopicRanker ranker;

  /** 読み上げ判断結果判断基準。 */
  private final SpeakDecisionPolicy speakDecisionPolicy;

  /** メッセージ生成。 */
  private final TopicMessageGenerator messageGenerator;

  /** 情報への関心実行待ち。 */
  private final CuriosityQueue curiosityQueue;

  /** この機能の動作設定。 */
  private final TopicGeneratorProperties properties;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 直近話題一覧。 */
  private final List<String> recentTopics = new ArrayList<>();

  /** 直近話題読み上げ済み日時。 */
  private Instant lastTopicSpokenAt;

  /** 指定された値や利用先を使って、TopicGeneratorServiceを初期化する。 */
  public TopicGeneratorService(
      List<TopicCandidateGenerator> generators,
      TopicRanker ranker,
      SpeakDecisionPolicy speakDecisionPolicy,
      TopicMessageGenerator messageGenerator,
      CuriosityQueue curiosityQueue,
      TopicGeneratorProperties properties,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher,
      Clock clock) {
    this.generators = generators == null ? List.of() : List.copyOf(generators);
    this.ranker = ranker;
    this.speakDecisionPolicy = speakDecisionPolicy;
    this.messageGenerator = messageGenerator;
    this.curiosityQueue = curiosityQueue;
    this.properties = properties;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
    this.clock = clock;
  }

  /** 話題の管理の操作を実行する。 */
  public synchronized TopicRunResult run(
      String runId, TopicGenerationContext context, boolean userRecentlyActive, boolean agentBusy) {
    return run(
        context,
        new TopicExecutionContext(
            runId,
            Instant.now(clock),
            null,
            lastTopicSpokenAt,
            agentBusy,
            TopicTrigger.CHAT_COMPLETED),
        userRecentlyActive);
  }

  /** 話題の管理の操作を実行する。 */
  public synchronized TopicRunResult run(
      TopicGenerationContext context, TopicExecutionContext executionContext) {
    boolean userRecentlyActive = false;
    if (executionContext.lastUserActivityAt() != null
        && executionContext.lastAgentActivityAt() != null) {
      Instant latest =
          executionContext.lastUserActivityAt().isAfter(executionContext.lastAgentActivityAt())
              ? executionContext.lastUserActivityAt()
              : executionContext.lastAgentActivityAt();
      userRecentlyActive =
          executionContext.trigger() != TopicTrigger.USER_IDLE
              && !latest.isBefore(
                  executionContext.now().minus(properties.getIdleTrigger().getMinimumIdle()));
    }
    return run(context, executionContext, userRecentlyActive);
  }

  /** 話題の管理で用いる準備候補一覧を返す。 */
  public synchronized List<TopicCandidate> prepareCandidates(TopicGenerationContext context) {
    if (!properties.isEnabled()) return List.of();
    return generateCandidates(context, true);
  }

  /** 依頼された内容を提供するため、候補一覧を生成する。 */
  private List<TopicCandidate> generateCandidates(
      TopicGenerationContext context, boolean ignoreFailures) {
    List<TopicCandidate> candidates = new ArrayList<>();
    for (TopicCandidateGenerator generator : generators) {
      try {
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(null);
        candidates.addAll(generator.generate(context));
      } catch (Exception e) {
        dev.mikoto2000.rei.core.chat.RunCancellation.propagate(e);
        if (!ignoreFailures) throw e;
        log.warn("Topic candidate refresh failed: {}", generator.getClass().getSimpleName());
      }
      if (candidates.size() >= properties.getMaxCandidates()) break;
    }
    return candidates.stream().limit(properties.getMaxCandidates()).toList();
  }

  /** 依頼された操作を進めるため、付き候補一覧を実行する。 */
  public synchronized TopicRunResult runWithCandidates(
      List<TopicCandidate> candidates, TopicExecutionContext executionContext) {
    return runWithCandidates(candidates, executionContext, false);
  }

  /** 話題の管理の操作を実行する。 */
  private TopicRunResult run(
      TopicGenerationContext context,
      TopicExecutionContext executionContext,
      boolean userRecentlyActive) {
    if (!properties.isEnabled()) return TopicRunResult.skipped("disabled");
    String topicGenerationId = "tg-" + UUID.randomUUID();
    eventPublisher.publish(
        eventFactory.topicGenerationStarted(
            executionContext.runId(), topicGenerationId, executionContext.trigger().name()));
    long started = System.nanoTime();
    try {
      List<TopicCandidate> candidates = generateCandidates(context, false);
      return runWithCandidates(
          candidates,
          executionContext,
          userRecentlyActive,
          topicGenerationId,
          started,
          false,
          true);
    } catch (RuntimeException exception) {
      fail(
          executionContext.runId(),
          topicGenerationId,
          TopicGenerationStage.CANDIDATE_GENERATION,
          exception);
      return TopicRunResult.skipped("topic generation failed");
    }
  }

  /** 依頼された操作を進めるため、付き候補一覧を実行する。 */
  private TopicRunResult runWithCandidates(
      List<TopicCandidate> providedCandidates,
      TopicExecutionContext executionContext,
      boolean userRecentlyActive) {
    return runWithCandidates(
        providedCandidates,
        executionContext,
        userRecentlyActive,
        "tg-" + UUID.randomUUID(),
        System.nanoTime(),
        true,
        false);
  }

  /** 依頼された操作を進めるため、付き候補一覧を実行する。 */
  private TopicRunResult runWithCandidates(
      List<TopicCandidate> providedCandidates,
      TopicExecutionContext executionContext,
      boolean userRecentlyActive,
      String topicGenerationId,
      long started,
      boolean publishStarted,
      boolean completeSpokenLifecycle) {
    String runId = executionContext.runId();
    boolean agentBusy = executionContext.agentBusy();
    if (publishStarted) {
      eventPublisher.publish(
          eventFactory.topicGenerationStarted(
              runId, topicGenerationId, executionContext.trigger().name()));
    }
    try {
      List<TopicCandidate> candidates =
          providedCandidates == null
              ? List.of()
              : providedCandidates.stream().limit(properties.getMaxCandidates()).toList();
      for (TopicCandidate candidate : candidates) {
        eventPublisher.publish(
            eventFactory.topicCandidateGenerated(
                runId,
                topicGenerationId,
                candidate.id(),
                candidate.type().name(),
                candidate.source().name(),
                candidate.topic(),
                candidate.reason(),
                candidate.priority(),
                candidate.freshness(),
                candidate.usefulness(),
                candidate.intrusiveness(),
                candidate.confidence()));
      }

      List<RankedTopicCandidate> ranked;
      try {
        ranked = ranker.rank(candidates, new TopicRankingContext(recentTopics));
      } catch (RuntimeException exception) {
        fail(runId, topicGenerationId, TopicGenerationStage.RANKING, exception);
        return TopicRunResult.skipped("ranking failed");
      }
      for (RankedTopicCandidate rankedCandidate : ranked) {
        eventPublisher.publish(
            eventFactory.topicCandidateScored(
                runId,
                topicGenerationId,
                rankedCandidate.candidate().id(),
                rankedCandidate.score()));
      }

      int rejectedCount = rejectUnselectable(runId, topicGenerationId, ranked);
      List<RankedTopicCandidate> eligible =
          ranked.stream().filter(candidate -> rejectionReason(candidate) == null).toList();
      SpeakDecision decision;
      try {
        decision =
            speakDecisionPolicy.decide(
                eligible,
                new SpeakDecisionContext(
                    executionContext.now(),
                    lastTopicSpokenAt,
                    recentTopics,
                    userRecentlyActive,
                    agentBusy));
      } catch (RuntimeException exception) {
        fail(runId, topicGenerationId, TopicGenerationStage.SPEAK_DECISION, exception);
        return TopicRunResult.skipped("speak decision failed");
      }
      if (decision.decision() == SpeakDecisionStatus.DO_NOT_SPEAK) {
        String selectedCandidateId = null;
        if (decision.reason() == TopicSpeakSkipReason.NO_CANDIDATE && !eligible.isEmpty()) {
          rejectedCount += rejectPolicyExcluded(runId, topicGenerationId, eligible);
        }
        boolean hasCandidateButSkipped =
            !eligible.isEmpty()
                && decision.reason() != TopicSpeakSkipReason.NO_CANDIDATE
                && decision.reason() != TopicSpeakSkipReason.BELOW_THRESHOLD
                && decision.reason() != TopicSpeakSkipReason.LOW_CONFIDENCE;
        if (hasCandidateButSkipped) {
          RankedTopicCandidate selected = eligible.getFirst();
          selectedCandidateId = selected.candidate().id();
          eventPublisher.publish(
              eventFactory.topicSelected(
                  runId, topicGenerationId, selectedCandidateId, selected.finalScore(), 1));
        }
        eventPublisher.publish(
            eventFactory.topicSpeakSkipped(
                runId,
                topicGenerationId,
                selectedCandidateId,
                decision.reason(),
                decision.nextSpeakAllowedAt()));
        eventPublisher.publish(
            eventFactory.topicGenerationCompleted(
                runId,
                topicGenerationId,
                candidates.size(),
                ranked.size(),
                rejectedCount,
                selectedCandidateId,
                false,
                elapsed(started)));
        log.info(
            "Topic generation skipped: candidateCount={}, decision={}, reason={}, durationMs={}",
            ranked.size(),
            decision.decision(),
            decision.reason(),
            elapsed(started));
        return TopicRunResult.skipped(
            decision.reason() == null ? "skipped" : decision.reason().name());
      }
      RankedTopicCandidate selected = decision.selected();
      TopicCandidate candidate = selected.candidate();
      eventPublisher.publish(
          eventFactory.topicSelected(
              runId, topicGenerationId, candidate.id(), selected.finalScore(), 1));
      String message;
      try {
        message =
            messageGenerator.generate(candidate, new TopicMessageContext(executionContext.now()));
      } catch (RuntimeException exception) {
        fail(runId, topicGenerationId, TopicGenerationStage.MESSAGE_GENERATION, exception);
        return TopicRunResult.skipped("message generation failed");
      }
      lastTopicSpokenAt = executionContext.now();
      recentTopics.add(candidate.topic());
      if (recentTopics.size() > properties.getMaxCandidates()) recentTopics.removeFirst();
      if (candidate.source() == TopicSource.CURIOSITY_QUEUE) {
        curiosityQueue
            .findCandidates(
                new CuriosityQuery(executionContext.now(), properties.getMaxCandidates()))
            .stream()
            .filter(
                item ->
                    DeterministicTopicRanker.normalize(item.question())
                        .equals(DeterministicTopicRanker.normalize(candidate.topic())))
            .findFirst()
            .ifPresent(item -> curiosityQueue.markUsed(item.id()));
      }
      TopicRunResult result =
          TopicRunResult.spoken(
              message,
              candidate,
              runId,
              topicGenerationId,
              topicGenerationId + "-message",
              candidates.size(),
              ranked.size(),
              rejectedCount,
              elapsed(started),
              selected.finalScore());
      if (completeSpokenLifecycle) {
        completeSpoken(result, lastTopicSpokenAt);
      }
      return result;
    } catch (RuntimeException exception) {
      fail(runId, topicGenerationId, TopicGenerationStage.CANDIDATE_GENERATION, exception);
      return TopicRunResult.skipped("topic generation failed");
    }
  }

  /** 受け付けられない条件を呼び出し元へ伝えるため、Unselectableを拒否する。 */
  private int rejectUnselectable(
      String runId, String topicGenerationId, List<RankedTopicCandidate> ranked) {
    int rejected = 0;
    for (RankedTopicCandidate candidate : ranked) {
      TopicRejectionReason reason = rejectionReason(candidate);
      if (reason == null) continue;
      rejected++;
      eventPublisher.publish(
          eventFactory.topicCandidateRejected(
              runId,
              topicGenerationId,
              candidate.candidate().id(),
              reason,
              candidate.finalScore()));
    }
    return rejected;
  }

  /** 受け付けられない条件を呼び出し元へ伝えるため、判断基準対象外を拒否する。 */
  private int rejectPolicyExcluded(
      String runId, String topicGenerationId, List<RankedTopicCandidate> ranked) {
    int rejected = 0;
    for (RankedTopicCandidate candidate : ranked) {
      if (candidate.rejectionReason() == null) continue;
      rejected++;
      eventPublisher.publish(
          eventFactory.topicCandidateRejected(
              runId,
              topicGenerationId,
              candidate.candidate().id(),
              candidate.rejectionReason(),
              candidate.finalScore()));
    }
    return rejected;
  }

  /** 話題の管理で用いるrejection理由を返す。 */
  private TopicRejectionReason rejectionReason(RankedTopicCandidate ranked) {
    if (ranked.finalScore() < properties.getMinimumScore()) return TopicRejectionReason.LOW_SCORE;
    if (ranked.candidate().confidence() < properties.getMinimumConfidence())
      return TopicRejectionReason.LOW_CONFIDENCE;
    return null;
  }

  /** 話題の管理に関するfailを処理する。 */
  private void fail(
      String runId, String topicGenerationId, TopicGenerationStage stage, Throwable exception) {
    eventPublisher.publish(
        eventFactory.topicGenerationFailed(runId, topicGenerationId, stage, exception));
    log.warn(
        "Topic generation failed: stage={}, topicGenerationId={}",
        stage,
        topicGenerationId,
        exception);
  }

  /** 話題の管理で用いる経過時間を返す。 */
  private long elapsed(long started) {
    return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
  }

  /** 実行結果を後続の処理へ引き渡すため、読み上げ済みを完了させる。 */
  public synchronized void completeSpoken(TopicRunResult result, Instant spokenAt) {
    if (result == null || !result.spoken() || result.lifecycleCompleted()) return;
    eventPublisher.publish(
        eventFactory.topicSpoken(
            result.runId(),
            result.topicGenerationId(),
            result.candidate().id(),
            result.messageId(),
            spokenAt,
            result.message()));
    eventPublisher.publish(
        eventFactory.topicGenerationCompleted(
            result.runId(),
            result.topicGenerationId(),
            result.candidateCount(),
            result.scoredCount(),
            result.rejectedCount(),
            result.candidate().id(),
            true,
            result.durationMs()));
    log.info(
        "Topic selected: candidateCount={}, selectedCandidateId={}, selectedType={}, score={}, durationMs={}",
        result.scoredCount(),
        result.candidate().id(),
        result.candidate().type(),
        result.finalScore(),
        result.durationMs());
  }

  /**
   * 話題の管理で受け渡す話題実行結果を表す。
   *
   * @param spoken 読み上げ済み
   * @param message メッセージ
   * @param candidate 候補
   * @param reason 理由
   * @param runId 実行の識別子
   * @param topicGenerationId 話題生成識別子
   * @param messageId メッセージ識別子
   * @param candidateCount 候補件数
   * @param scoredCount 評価済み件数
   * @param rejectedCount 拒否済み件数
   * @param durationMs 継続時間ミリ秒
   * @param finalScore 最終評価値
   * @param lifecycleCompleted 受付から終了までの管理完了
   */
  public record TopicRunResult(
      boolean spoken,
      String message,
      TopicCandidate candidate,
      String reason,
      String runId,
      String topicGenerationId,
      String messageId,
      int candidateCount,
      int scoredCount,
      int rejectedCount,
      long durationMs,
      double finalScore,
      boolean lifecycleCompleted) {
    /** 話題の管理で用いる対象外を返す。 */
    static TopicRunResult skipped(String reason) {
      return new TopicRunResult(false, null, null, reason, null, null, null, 0, 0, 0, 0, 0, true);
    }

    /** 話題の管理で用いる読み上げ済みを返す。 */
    static TopicRunResult spoken(
        String message,
        TopicCandidate candidate,
        String runId,
        String topicGenerationId,
        String messageId,
        int candidateCount,
        int scoredCount,
        int rejectedCount,
        long durationMs,
        double finalScore) {
      return new TopicRunResult(
          true,
          message,
          candidate,
          null,
          runId,
          topicGenerationId,
          messageId,
          candidateCount,
          scoredCount,
          rejectedCount,
          durationMs,
          finalScore,
          false);
    }
  }
}
