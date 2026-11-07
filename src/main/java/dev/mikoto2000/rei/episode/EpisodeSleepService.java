package dev.mikoto2000.rei.episode;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import dev.mikoto2000.rei.llm.ModelCallBudget;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

/** 経験の記録待機のユースケースを実現する。 */
@Service
@EnableConfigurationProperties(EpisodeProperties.class)
public class EpisodeSleepService {
  /** 保存と検索の窓口。 */
  private final EpisodeRepository repository;

  /** やり取り一覧。 */
  private final java.util.function.ToLongFunction<String> turnCount;

  private record TurnRange(String session, long from, int limit) {}

  private final java.util.function.Function<TurnRange, java.util.List<ConversationTurnStore.Turn>>
      readRange;

  /** 記録された経験を整理する処理の窓口。 */
  private final ProcessBatch<ConversationTurnStore.Turn, ModelCallBudget> processBatch;

  /** この機能の動作設定。 */
  private final EpisodeProperties properties;

  /** 記憶。 */
  private final MemoryProperties memory;

  /** ベクトルによる。 */
  private BiConsumer<String, ModelCallBudget> indexPending = (session, budget) -> {};

  private ToIntFunction<String> pendingIndex = session -> 0;

  /** 過去の経験の活用で使用するベクトルによるを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDense(EpisodeDenseIndex dense) {
    this.indexPending = dense == null ? (session, budget) -> {} : dense::indexPending;
    this.pendingIndex = dense == null ? session -> 0 : dense::pending;
  }

  /** 作業。 */
  private org.springframework.beans.factory.ObjectProvider<
          dev.mikoto2000.rei.workcontext.WorkContextService>
      work;

  /** 過去の経験の活用で使用する作業文脈を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setWorkContext(
      org.springframework.beans.factory.ObjectProvider<
              dev.mikoto2000.rei.workcontext.WorkContextService>
          work) {
    this.work = work;
  }

  /** 指定された値や利用先を使って、EpisodeSleepServiceを初期化する。 */
  public EpisodeSleepService(
      EpisodeRepository repository,
      ConversationTurnStore turns,
      EpisodeProcessor processor,
      EpisodeProperties properties,
      MemoryProperties memory) {
    this.repository = repository;
    this.turnCount = session -> turns.turnCount(session);
    this.readRange = range -> turns.readRange(range.session(), range.from(), range.limit());
    this.processBatch =
        (range, batch, budget) ->
            processor.process(
                range.session(), range.project(), range.from(), range.to(), batch, budget);
    this.properties = properties;
    this.memory = memory;
  }

  /** Processes pending episodes using the memory run's existing model-call accounting. */
  public void processWithMemoryBudget(
      String session,
      String project,
      Runnable beforeModelCall,
      java.util.function.BooleanSupplier cancelled) {
    process(session, project, ModelCallBudget.fromCallback(beforeModelCall), cancelled);
  }

  /** 過去の経験の活用に関するプロセスを処理する。 */
  public void process(
      String session,
      String project,
      ModelCallBudget budget,
      java.util.function.BooleanSupplier cancelled) {
    if (!properties.enabled() || !memory.enabled()) return;
    String worker = UUID.randomUUID().toString();
    if (!repository.acquire(
        session,
        worker,
        budget instanceof dev.mikoto2000.rei.memory.service.AutoSleepExecution auto
            ? auto.remaining().toSeconds() + 60
            : (long) memory.sleep().timeoutSeconds() + 60))
      throw new dev.mikoto2000.rei.memory.service.SleepService.SleepBusyException();
    try {
      long from = repository.checkpoint(session), to = from;
      int tokens = 0;
      var batch = new ArrayList<ConversationTurnStore.Turn>();
      for (var turn :
          readRange.apply(new TurnRange(session, from, Math.min(50, memory.sleep().maxTurns())))) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())
          throw new java.util.concurrent.CancellationException();
        if (turn.status() == ConversationTurnStore.Status.RUNNING) break;
        int size =
            dev.mikoto2000.rei.core.contextbudget.TokenEstimator.conservative()
                    .text(
                        Objects.toString(turn.request(), "")
                            + Objects.toString(turn.assistantMessage(), ""))
                + 64;
        if (tokens + size > memory.sleep().maxInputTokens()) break;
        to++;
        tokens += size;
        batch.add(turn);
      }
      if (to > from)
        processBatch.process(
            new ProcessRange(session, project, from, to), List.copyOf(batch), budget);
      indexPending.accept(session, budget);
      if (work != null && work.getIfAvailable() != null)
        work.getObject()
            .current(project)
            .ifPresent(context -> repository.linkWorkContext(context, session));
    } finally {
      repository.release(session, worker);
    }
  }

  /** 過去の経験の活用に関するリンク記憶を処理する。 */
  public void linkMemory(
      String project, String session, java.util.List<String> runs, String memoryId) {
    if (properties.enabled()) repository.linkMemory(project, session, runs, memoryId);
  }

  /** 後続の処理で判断できるよう、未完了の対象を返す。 */
  public long pending(String session) {
    return properties.enabled()
        ? Math.max(
            Math.max(0, turnCount.applyAsLong(session) - repository.checkpoint(session)),
            pendingIndex.applyAsInt(session))
        : 0;
  }

  private record ProcessRange(String session, String project, long from, long to) {}

  @FunctionalInterface
  private interface ProcessBatch<T, B> {
    void process(ProcessRange range, List<T> turns, B budget);
  }
}
