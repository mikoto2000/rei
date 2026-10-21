package dev.mikoto2000.rei.episode;

import dev.mikoto2000.rei.conversation.ConversationTurnStore.Turn;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** 過去の経験の活用に必要な経験の記録Processorを扱う。 */
@Service
public class EpisodeProcessor {
  /** 保存と検索の窓口。 */
  private final EpisodeRepository repository;

  /** 情報の抽出。 */
  private final EpisodeExtractor extractor;

  /** 保存先。 */
  private final dev.mikoto2000.rei.conversation.ConversationTurnStore store;

  /** イベント一覧。 */
  private dev.mikoto2000.rei.event.ProjectAgentEventStore events;

  /** 過去の経験の活用で使用するイベント一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setEvents(dev.mikoto2000.rei.event.ProjectAgentEventStore events) {
    this.events = events;
  }

  /** 過去の経験の活用を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public EpisodeProcessor(EpisodeRepository repository, EpisodeExtractor extractor) {
    this(repository, extractor, null);
  }

  /** 指定された値や利用先を使って、EpisodeProcessorを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public EpisodeProcessor(
      EpisodeRepository repository,
      EpisodeExtractor extractor,
      dev.mikoto2000.rei.conversation.ConversationTurnStore store) {
    this.repository = repository;
    this.extractor = extractor;
    this.store = store;
  }

  /** 過去の経験の活用に関するプロセスを処理する。 */
  public void process(
      String session,
      String project,
      long from,
      long to,
      List<Turn> turns,
      ModelCallBudget budget) {
    var existing =
        turns.isEmpty()
            ? List.<Episode>of()
            : repository.search(turns.getLast().request(), project, 10);
    if (existing == null) existing = List.of();
    existing =
        existing.stream()
            .filter(
                e ->
                    store != null
                        && e.claims().stream()
                            .allMatch(
                                c ->
                                    store.findRun(e.sessionId(), c.runId()).isPresent()
                                        && (!c.speaker().equals("tool")
                                            || events != null
                                                && events
                                                    .findEvent(e.projectId(), c.sourceId())
                                                    .isPresent())))
            .toList();
    var candidates =
        turns.isEmpty()
            ? List.<Episode>of()
            : extractor.extract(session, project, turns, existing, budget);
    var source = new HashMap<String, Turn>();
    for (var turn : turns) source.put(turn.runId(), turn);
    var verified = new ArrayList<Episode>();
    for (var candidate : candidates) {
      // 別のプロジェクトの情報を、この作業の対象として扱わせない。
      if (!candidate.projectId().equals(project) || !candidate.sessionId().equals(session))
        throw new IllegalArgumentException("Episode ownership mismatch");
      if (turns.stream()
          .noneMatch(
              t ->
                  t.createdAt() != null
                      && t.createdAt().equals(java.time.Instant.parse(candidate.occurredAt()))))
        throw new IllegalArgumentException("Episode timestamp is not in supplied evidence");
      if (candidate.endedAt() != null
          && turns.stream()
              .noneMatch(
                  t ->
                      t.createdAt() != null
                          && t.createdAt().equals(java.time.Instant.parse(candidate.endedAt()))))
        throw new IllegalArgumentException("Episode end is not in supplied evidence");
      for (var claim : candidate.claims()) {
        var turn = source.get(claim.runId());
        if (turn == null)
          throw new IllegalArgumentException("Episode cites a turn outside the batch");
        if (claim.speaker().equals("tool")) {
          var event =
              events == null ? null : events.findEvent(project, claim.sourceId()).orElse(null);
          if (event == null
              || !session.equals(event.sessionId())
              || !claim.runId().equals(event.runId())
              || !(event.payload() instanceof dev.mikoto2000.rei.event.ToolCompletedPayload tool)
              || !Objects.toString(tool.resultSummary(), "").contains(claim.text()))
            throw new IllegalArgumentException(
                "Tool evidence requires an exact retained tool event quotation");
        }
        if (claim.evidence() == Episode.Evidence.USER_EXPLICIT
            && !Objects.toString(turn.request(), "").contains(claim.text()))
          throw new IllegalArgumentException(
              "Explicit user evidence requires an exact source quotation");
      }
      if (candidate.status() == Episode.Status.COMPLETED
          && candidate.claims().stream()
              .noneMatch(
                  c ->
                      c.kind().equals("decision") && c.evidence() == Episode.Evidence.USER_EXPLICIT
                          || c.kind().equals("result")
                              && c.evidence() == Episode.Evidence.TOOL_OBSERVED))
        throw new IllegalArgumentException(
            "A completed episode requires an explicit user decision or direct tool result");
      verified.add(candidate);
    }
    if (store != null)
      for (var turn : turns)
        if (!store.findRun(session, turn.runId()).filter(turn::equals).isPresent())
          throw new IllegalStateException("Episode source changed during extraction; retry");
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
    if (budget instanceof dev.mikoto2000.rei.memory.service.AutoSleepExecution auto)
      auto.remaining();
    repository.saveBatch(session, from, to, verified);
    if (to > from && budget instanceof dev.mikoto2000.rei.memory.service.AutoSleepExecution auto)
      auto.progressSaved();
  }
}
