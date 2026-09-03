package dev.mikoto2000.rei.topic;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;

public class TopicGeneratorService {
  private static final Logger log = LoggerFactory.getLogger(TopicGeneratorService.class);

  private final List<TopicCandidateGenerator> generators;
  private final TopicRanker ranker;
  private final SpeakDecisionPolicy speakDecisionPolicy;
  private final TopicMessageGenerator messageGenerator;
  private final CuriosityQueue curiosityQueue;
  private final TopicGeneratorProperties properties;
  private final AgentEventFactory eventFactory;
  private final AgentEventPublisher eventPublisher;
  private final Clock clock;
  private final List<String> recentTopics = new ArrayList<>();
  private Instant lastTopicSpokenAt;

  public TopicGeneratorService(List<TopicCandidateGenerator> generators, TopicRanker ranker,
      SpeakDecisionPolicy speakDecisionPolicy, TopicMessageGenerator messageGenerator, CuriosityQueue curiosityQueue,
      TopicGeneratorProperties properties, AgentEventFactory eventFactory, AgentEventPublisher eventPublisher,
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

  public synchronized TopicRunResult run(String runId, TopicGenerationContext context, boolean userRecentlyActive,
      boolean agentBusy) {
    return run(context, new TopicExecutionContext(runId, Instant.now(clock), null, lastTopicSpokenAt, agentBusy,
        TopicTrigger.CHAT_COMPLETED), userRecentlyActive);
  }

  public synchronized TopicRunResult run(TopicGenerationContext context, TopicExecutionContext executionContext) {
    boolean userRecentlyActive = false;
    if (executionContext.lastUserActivityAt() != null && executionContext.lastAgentActivityAt() != null) {
      Instant latest = executionContext.lastUserActivityAt().isAfter(executionContext.lastAgentActivityAt())
          ? executionContext.lastUserActivityAt()
          : executionContext.lastAgentActivityAt();
      userRecentlyActive = executionContext.trigger() != TopicTrigger.USER_IDLE
          && !latest.isBefore(executionContext.now().minus(properties.getIdleTrigger().getMinimumIdle()));
    }
    return run(context, executionContext, userRecentlyActive);
  }

  public synchronized List<TopicCandidate> prepareCandidates(TopicGenerationContext context) {
    if (!properties.isEnabled()) return List.of();
    return generateCandidates(context, true);
  }

  private List<TopicCandidate> generateCandidates(TopicGenerationContext context, boolean ignoreFailures) {
    List<TopicCandidate> candidates = new ArrayList<>();
    for (TopicCandidateGenerator generator : generators) {
      try {
        candidates.addAll(generator.generate(context));
      } catch (Exception e) {
        if (!ignoreFailures) throw e;
        log.warn("Topic candidate refresh failed: {}", generator.getClass().getSimpleName());
      }
      if (candidates.size() >= properties.getMaxCandidates()) break;
    }
    return candidates.stream().limit(properties.getMaxCandidates()).toList();
  }

  public synchronized TopicRunResult runWithCandidates(List<TopicCandidate> candidates,
      TopicExecutionContext executionContext) {
    return runWithCandidates(candidates, executionContext, false);
  }

  private TopicRunResult run(TopicGenerationContext context, TopicExecutionContext executionContext,
      boolean userRecentlyActive) {
    if (!properties.isEnabled()) return TopicRunResult.skipped("disabled");
    String topicGenerationId = "tg-" + UUID.randomUUID();
    eventPublisher.publish(eventFactory.topicGenerationStarted(executionContext.runId(), topicGenerationId,
        executionContext.trigger().name()));
    long started = System.nanoTime();
    try {
      List<TopicCandidate> candidates = generateCandidates(context, false);
      return runWithCandidates(candidates, executionContext, userRecentlyActive, topicGenerationId, started, false,
          true);
    } catch (RuntimeException exception) {
      fail(executionContext.runId(), topicGenerationId, TopicGenerationStage.CANDIDATE_GENERATION, exception);
      return TopicRunResult.skipped("topic generation failed");
    }
  }

  private TopicRunResult runWithCandidates(List<TopicCandidate> providedCandidates,
      TopicExecutionContext executionContext, boolean userRecentlyActive) {
    return runWithCandidates(providedCandidates, executionContext, userRecentlyActive, "tg-" + UUID.randomUUID(),
        System.nanoTime(), true, false);
  }

  private TopicRunResult runWithCandidates(List<TopicCandidate> providedCandidates,
      TopicExecutionContext executionContext, boolean userRecentlyActive, String topicGenerationId, long started,
      boolean publishStarted, boolean completeSpokenLifecycle) {
    String runId = executionContext.runId();
    boolean agentBusy = executionContext.agentBusy();
    if (publishStarted) {
      eventPublisher.publish(eventFactory.topicGenerationStarted(runId, topicGenerationId,
          executionContext.trigger().name()));
    }
    try {
      List<TopicCandidate> candidates = providedCandidates == null ? List.of()
          : providedCandidates.stream().limit(properties.getMaxCandidates()).toList();
      for (TopicCandidate candidate : candidates) {
        eventPublisher.publish(eventFactory.topicCandidateGenerated(runId, topicGenerationId, candidate.id(),
            candidate.type().name(), candidate.source().name(), candidate.topic(), candidate.reason(),
            candidate.priority(), candidate.freshness(), candidate.usefulness(), candidate.intrusiveness(),
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
        eventPublisher.publish(eventFactory.topicCandidateScored(runId, topicGenerationId,
            rankedCandidate.candidate().id(), rankedCandidate.score()));
      }

      int rejectedCount = rejectUnselectable(runId, topicGenerationId, ranked);
      List<RankedTopicCandidate> eligible = ranked.stream()
          .filter(candidate -> rejectionReason(candidate) == null)
          .toList();
      SpeakDecision decision;
      try {
        decision = speakDecisionPolicy.decide(eligible, new SpeakDecisionContext(
            executionContext.now(), lastTopicSpokenAt, recentTopics, userRecentlyActive, agentBusy));
      } catch (RuntimeException exception) {
        fail(runId, topicGenerationId, TopicGenerationStage.SPEAK_DECISION, exception);
        return TopicRunResult.skipped("speak decision failed");
      }
      if (decision.decision() == SpeakDecisionStatus.DO_NOT_SPEAK) {
        String selectedCandidateId = null;
        if (decision.reason() == TopicSpeakSkipReason.NO_CANDIDATE && !eligible.isEmpty()) {
          rejectedCount += rejectPolicyExcluded(runId, topicGenerationId, eligible);
        }
        boolean hasCandidateButSkipped = !eligible.isEmpty()
            && decision.reason() != TopicSpeakSkipReason.NO_CANDIDATE
            && decision.reason() != TopicSpeakSkipReason.BELOW_THRESHOLD
            && decision.reason() != TopicSpeakSkipReason.LOW_CONFIDENCE;
        if (hasCandidateButSkipped) {
          RankedTopicCandidate selected = eligible.getFirst();
          selectedCandidateId = selected.candidate().id();
          eventPublisher.publish(eventFactory.topicSelected(runId, topicGenerationId, selectedCandidateId,
              selected.finalScore(), 1));
        }
        eventPublisher.publish(eventFactory.topicSpeakSkipped(runId, topicGenerationId, selectedCandidateId,
            decision.reason(), decision.nextSpeakAllowedAt()));
        eventPublisher.publish(eventFactory.topicGenerationCompleted(runId, topicGenerationId, candidates.size(),
            ranked.size(), rejectedCount, selectedCandidateId, false, elapsed(started)));
        log.info("Topic generation skipped: candidateCount={}, decision={}, reason={}, durationMs={}",
            ranked.size(), decision.decision(), decision.reason(), elapsed(started));
        return TopicRunResult.skipped(decision.reason() == null ? "skipped" : decision.reason().name());
      }
      RankedTopicCandidate selected = decision.selected();
      TopicCandidate candidate = selected.candidate();
      eventPublisher.publish(eventFactory.topicSelected(runId, topicGenerationId, candidate.id(), selected.finalScore(),
          1));
      String message;
      try {
        message = messageGenerator.generate(candidate, new TopicMessageContext(executionContext.now()));
      } catch (RuntimeException exception) {
        fail(runId, topicGenerationId, TopicGenerationStage.MESSAGE_GENERATION, exception);
        return TopicRunResult.skipped("message generation failed");
      }
      lastTopicSpokenAt = executionContext.now();
      recentTopics.add(candidate.topic());
      if (recentTopics.size() > properties.getMaxCandidates()) recentTopics.removeFirst();
      if (candidate.source() == TopicSource.CURIOSITY_QUEUE) {
        curiosityQueue.findCandidates(new CuriosityQuery(executionContext.now(), properties.getMaxCandidates())).stream()
            .filter(item -> DeterministicTopicRanker.normalize(item.question())
                .equals(DeterministicTopicRanker.normalize(candidate.topic())))
            .findFirst()
            .ifPresent(item -> curiosityQueue.markUsed(item.id()));
      }
      TopicRunResult result = TopicRunResult.spoken(message, candidate, runId, topicGenerationId,
          topicGenerationId + "-message", candidates.size(), ranked.size(), rejectedCount, elapsed(started),
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

  private int rejectUnselectable(String runId, String topicGenerationId, List<RankedTopicCandidate> ranked) {
    int rejected = 0;
    for (RankedTopicCandidate candidate : ranked) {
      TopicRejectionReason reason = rejectionReason(candidate);
      if (reason == null) continue;
      rejected++;
      eventPublisher.publish(eventFactory.topicCandidateRejected(runId, topicGenerationId, candidate.candidate().id(),
          reason, candidate.finalScore()));
    }
    return rejected;
  }

  private int rejectPolicyExcluded(String runId, String topicGenerationId, List<RankedTopicCandidate> ranked) {
    int rejected = 0;
    for (RankedTopicCandidate candidate : ranked) {
      if (candidate.rejectionReason() == null) continue;
      rejected++;
      eventPublisher.publish(eventFactory.topicCandidateRejected(runId, topicGenerationId, candidate.candidate().id(),
          candidate.rejectionReason(), candidate.finalScore()));
    }
    return rejected;
  }

  private TopicRejectionReason rejectionReason(RankedTopicCandidate ranked) {
    if (ranked.finalScore() < properties.getMinimumScore()) return TopicRejectionReason.LOW_SCORE;
    if (ranked.candidate().confidence() < properties.getMinimumConfidence()) return TopicRejectionReason.LOW_CONFIDENCE;
    return null;
  }

  private void fail(String runId, String topicGenerationId, TopicGenerationStage stage, Throwable exception) {
    eventPublisher.publish(eventFactory.topicGenerationFailed(runId, topicGenerationId, stage, exception));
    log.warn("Topic generation failed: stage={}, topicGenerationId={}", stage, topicGenerationId, exception);
  }

  private long elapsed(long started) {
    return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
  }

  public synchronized void completeSpoken(TopicRunResult result, Instant spokenAt) {
    if (result == null || !result.spoken() || result.lifecycleCompleted()) return;
    eventPublisher.publish(eventFactory.topicSpoken(result.runId(), result.topicGenerationId(), result.candidate().id(),
        result.messageId(), spokenAt, result.message()));
    eventPublisher.publish(eventFactory.topicGenerationCompleted(result.runId(), result.topicGenerationId(),
        result.candidateCount(), result.scoredCount(), result.rejectedCount(), result.candidate().id(), true,
        result.durationMs()));
    log.info("Topic selected: candidateCount={}, selectedCandidateId={}, selectedType={}, score={}, durationMs={}",
        result.scoredCount(), result.candidate().id(), result.candidate().type(), result.finalScore(),
        result.durationMs());
  }

  public record TopicRunResult(boolean spoken, String message, TopicCandidate candidate, String reason,
      String runId, String topicGenerationId, String messageId, int candidateCount, int scoredCount, int rejectedCount,
      long durationMs, double finalScore, boolean lifecycleCompleted) {
    static TopicRunResult skipped(String reason) {
      return new TopicRunResult(false, null, null, reason, null, null, null, 0, 0, 0, 0, 0, true);
    }

    static TopicRunResult spoken(String message, TopicCandidate candidate, String runId, String topicGenerationId,
        String messageId, int candidateCount, int scoredCount, int rejectedCount, long durationMs, double finalScore) {
      return new TopicRunResult(true, message, candidate, null, runId, topicGenerationId, messageId, candidateCount,
          scoredCount, rejectedCount, durationMs, finalScore, false);
    }
  }
}
