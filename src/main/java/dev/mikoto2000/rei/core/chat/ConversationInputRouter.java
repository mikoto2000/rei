package dev.mikoto2000.rei.core.chat;

import dev.mikoto2000.rei.core.execution.ActiveExecution;
import dev.mikoto2000.rei.core.execution.ExecutionScope;
import dev.mikoto2000.rei.core.execution.ExecutionType;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 会話への入力を実行状況に応じて振り分け、新規実行や追加入力として受け付ける。
 *
 * <p>Owns input mailboxes and active views; ProjectRunQueue owns all AGENT scheduling.
 */
public final class ConversationInputRouter {
  /** 会話の実行における扱いの選択肢。 */
  public enum Disposition {
    /** 開始。 */
    STARTED,
    /** 待機中。 */
    QUEUED
  }

  /** 会話の実行に必要な実行の担当を扱う。 */
  @FunctionalInterface
  public interface Runner {
    /** 要求された処理を実行する。 */
    void execute(AgentRunContext context, String prompt, UserInterventionQueue queue);
  }

  /** 会話の実行に必要な購読を扱う。 */
  @FunctionalInterface
  public interface Subscription extends AutoCloseable {
    /** 会話の実行で利用した資源や登録を解放し、利用を終了する。 */
    void close();
  }

  /** 要求取得記録一覧。 */
  private dev.mikoto2000.rei.llm.capture.CaptureStore requestCaptures;

  /** 受付台帳。 */
  private dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions;

  /** 会話の実行で使用する受付台帳を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setAdmissions(dev.mikoto2000.rei.conversation.ConversationAdmissionStore admissions) {
    this.admissions = admissions;
  }

  /** 会話の実行で使用する要求取得記録一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setRequestCaptures(dev.mikoto2000.rei.llm.capture.CaptureStore captures) {
    requestCaptures = captures;
  }

  /** 後片付け観測先一覧。 */
  private final java.util.List<Consumer<AgentRunContext>> cleanupObservers =
      new java.util.concurrent.CopyOnWriteArrayList<>();

  /** 通知に応じて状態や表示を更新するため、後片付けを受け付ける。 */
  public void onCleanup(Consumer<AgentRunContext> observer) {
    cleanupObservers.add(Objects.requireNonNull(observer));
  }

  /** 実行先。 */
  private final Executor executor;

  /** 実行の担当。 */
  private final Runner runner;

  /** プロジェクト実行待ち。 */
  private final ProjectRunQueue projectQueue;

  /** 受信。 */
  private final BiConsumer<AgentRunContext, UserInterventionQueue.Entry> received;

  /** 会話ごとの追加入力の受け取り先。 */
  private final Map<String, Slot> mailboxes = new HashMap<>();

  /** エージェントSlots。 */
  private final Map<String, Slot> agentSlots = new java.util.concurrent.ConcurrentHashMap<>();

  /** 活動中。 */
  private final Map<String, ActiveRun> active = new java.util.concurrent.ConcurrentHashMap<>();

  /** バックグラウンド。 */
  private final Map<String, ActiveExecution> background =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** 通知の受け取り先一覧。 */
  private final List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** 変更前実行。 */
  private final Map<String, Runnable> beforeExecution =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * 本処理に必要な文脈や条件を整えるため、実行を準備する。
   *
   * <p>Scoped presentation observer, captured before enqueue and run before lifecycle output. The
   * observer must not reenter admission/selection APIs.
   */
  public Subscription beforeExecution(String runId, Runnable observer) {
    if (beforeExecution.putIfAbsent(runId, Objects.requireNonNull(observer)) != null)
      throw new IllegalStateException("Run already has an admission observer");
    return () -> beforeExecution.remove(runId, observer);
  }

  /**
   * 会話の実行で受け渡すSlotを表す。
   *
   * @param context 文脈
   * @param prompt プロンプト
   * @param queue 実行待ち
   */
  private record Slot(AgentRunContext context, String prompt, UserInterventionQueue queue) {}

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ConversationInputRouter(Executor executor, Runner runner) {
    this(executor, runner, (c, e) -> {});
  }

  /** 指定された値や利用先を使って、ConversationInputRouterを初期化する。 */
  public ConversationInputRouter(
      Executor executor,
      Runner runner,
      BiConsumer<AgentRunContext, UserInterventionQueue.Entry> received) {
    this.executor = executor;
    this.runner = runner;
    this.received = received;
    this.projectQueue = new ProjectRunQueue(executor);
  }

  /** 通知に応じて状態や表示を更新するため、変更を受け付ける。 */
  public Subscription onChange(Runnable listener) {
    listeners.add(listener);
    return () -> listeners.remove(listener);
  }

  /** 会話の実行で用いる活動中実行一覧を返す。 */
  public List<ActiveRun> activeRuns() {
    return active.values().stream()
        .sorted(Comparator.comparing(ActiveRun::startedAt).thenComparing(ActiveRun::runId))
        .toList();
  }

  /** 会話の実行で用いる活動中実行一覧を返す。 */
  public List<ActiveExecution> activeExecutions() {
    var result = new ArrayList<>(background.values());
    active.values().forEach(run -> result.add(ActiveExecution.fromAgent(run)));
    return result.stream()
        .sorted(Comparator.comparing(ActiveExecution::startedAt).thenComparing(ActiveExecution::id))
        .toList();
  }

  /** 依頼された操作を進めるため、Auxiliaryを実行する。 */
  public void executeAuxiliary(Runnable work) {
    executor.execute(work);
  }

  /** 依頼を実行経路へ渡すため、バックグラウンドを受け付ける。 */
  public ActiveExecution submitBackground(
      ProjectContext project, ExecutionType type, String summary, Consumer<ActiveExecution> work) {
    return submitBackground(project, type, summary, e -> {}, work);
  }

  /** 依頼を実行経路へ渡すため、バックグラウンドを受け付ける。 */
  public ActiveExecution submitBackground(
      ProjectContext project,
      ExecutionType type,
      String summary,
      Consumer<ActiveExecution> registered,
      Consumer<ActiveExecution> work) {
    if (type == ExecutionType.AGENT)
      throw new IllegalArgumentException("Agent input must use its intervention router");
    String conversation = dev.mikoto2000.rei.core.project.ProjectService.selectedShellSession();
    if (conversation == null || !conversation.startsWith("project:" + project.id() + ":")) {
      conversation = project.conversationId(dev.mikoto2000.rei.llm.ConversationIds.chat());
    }
    var execution =
        new ActiveExecution(
            UUID.randomUUID().toString(),
            project.id(),
            conversation,
            project.root(),
            type,
            ActiveRun.summary(summary),
            java.time.Instant.now());
    background.put(execution.id(), execution);
    changed();
    try {
      registered.accept(execution);
      executor.execute(
          () -> {
            try (var scope = ExecutionScope.open(execution)) {
              work.accept(execution);
            } finally {
              background.remove(execution.id());
              changed();
            }
          });
    } catch (RuntimeException error) {
      background.remove(execution.id());
      changed();
      throw error;
    }
    return execution;
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public Disposition submit(Path root, String conversation, String prompt) {
    Slot created;
    synchronized (mailboxes) {
      var existing = mailboxes.get(conversation);
      if (existing != null && existing.queue().offer(prompt)) return Disposition.QUEUED;
      created = slot(cliContext(root, conversation), prompt);
      mailboxes.put(conversation, created);
    }
    enqueue(created, Runnable::run);
    return Disposition.STARTED;
  }

  /** 依頼を実行経路へ渡すため、新規実行を受け付ける。 */
  public Disposition submitNewRun(Path root, String conversation, String prompt) {
    return submit(cliContext(root, conversation), prompt);
  }

  /** 依頼を受け付け、実行を担当する処理へ引き渡す。 */
  public Disposition submit(AgentRunContext context, String prompt) {
    return submit(context, prompt, Runnable::run);
  }

  /**
   * 依頼を受け付け、実行を担当する処理へ引き渡す。
   *
   * <p>Application lifecycle wraps the runner without reassigning its identity or enqueueing twice.
   */
  public Disposition submit(AgentRunContext context, String prompt, Consumer<Runnable> lifecycle) {
    return enqueue(slot(context, prompt), lifecycle);
  }

  /** 承認後の会話を、同じ実行の追加入力を受け付けるキューで続行する。 */
  public Disposition submitContinuation(
      AgentRunContext context,
      String prompt,
      Consumer<UserInterventionQueue> operation,
      Consumer<Runnable> lifecycle) {
    Objects.requireNonNull(operation);
    return enqueue(
        slot(context, prompt), lifecycle, (owner, request, queue) -> operation.accept(queue));
  }

  /** 会話の実行で判断に用いる含有実行の条件を確認する。 */
  public boolean containsRun(String projectId, String runId) {
    return projectQueue.containsRun(projectId, runId);
  }

  /** 会話の実行において、受付追加入力に該当するか判定する。 */
  public boolean offerIntervention(
      String projectId, String sessionId, String runId, String message) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (message == null || message.isBlank() || message.length() > 16384)
      throw new IllegalArgumentException("Invalid guidance");
    var slot = agentSlots.get(runId);
    return slot != null
        && Objects.equals(projectId, slot.context().projectId())
        && Objects.equals(sessionId, slot.context().conversationId())
        && slot.queue().offer(message);
  }

  /** 会話の実行において、待機中かどうかを判定する。 */
  public boolean isQueued(String projectId, String runId) {
    return projectQueue.isQueued(projectId, runId);
  }

  /** 利用者が不要とした実行を止めるため、待機中をキャンセルする。 */
  public boolean cancelQueued(String runId) {
    boolean removed = projectQueue.cancelQueued(runId);
    if (removed) {
      if (requestCaptures != null) requestCaptures.finish(runId, "CANCELLED");
      var slot = agentSlots.remove(runId);
      if (slot != null) forget(slot);
    }
    return removed;
  }

  /**
   * 依頼を実行経路へ渡すため、操作を受け付ける。
   *
   * <p>Explicit application work shares the agent FIFO without entering the chat runner or mailbox.
   */
  public void submitOperation(
      AgentRunContext context, Runnable operation, Consumer<Runnable> lifecycle) {
    var slot = slot(context, "operation");
    if (admissions != null) admissions.reserveKnownSession(context);
    try {
      projectQueue.enqueue(
          context.projectId(),
          context.runId(),
          ProjectRunQueue.Access.valueOf(context.mode().name()),
          () -> {
            try {
              if (admissions != null) admissions.startExecution(context);
              lifecycle.accept(operation);
            } finally {
              forget(slot);
            }
          },
          () -> {
            active.put(context.runId(), ActiveRun.of(context, "operation"));
            changed();
          },
          () -> {
            if (admissions != null)
              admissions.transition(
                  context.runId(), dev.mikoto2000.rei.application.run.RunStatus.CANCELLED);
            forget(slot);
          },
          error -> {
            if (admissions != null)
              admissions.transition(
                  context.runId(), dev.mikoto2000.rei.application.run.RunStatus.FAILED);
            try {
              lifecycle.accept(
                  () -> {
                    throw error;
                  });
            } finally {
              forget(slot);
            }
          },
          context.requestSource() == AgentRunContext.RequestSource.MCP);
    } catch (RuntimeException error) {
      if (admissions != null)
        admissions.transition(context.runId(), dev.mikoto2000.rei.application.run.RunStatus.FAILED);
      forget(slot);
      throw error;
    }
  }

  /** 会話の実行で用いる軽量CLI文脈を返す。 */
  private AgentRunContext cliContext(Path root, String conversation) {
    return new AgentRunContext(
        UUID.randomUUID().toString(),
        conversation,
        root,
        dev.mikoto2000.rei.core.project.ProjectStorage.projectId(conversation));
  }

  /** 会話の実行で用いるslotを返す。 */
  private Slot slot(AgentRunContext context, String prompt) {
    return new Slot(
        context, prompt, new UserInterventionQueue(entry -> received.accept(context, entry)));
  }

  /** 実行順序を管理するため、会話入力入力の振り分けを実行待ちに登録する。 */
  private Disposition enqueue(Slot slot, Consumer<Runnable> lifecycle) {
    return enqueue(slot, lifecycle, runner);
  }

  /** 会話の実行と承認後の続行を、同じ受付・後片付けの経路へ登録する。 */
  private Disposition enqueue(Slot slot, Consumer<Runnable> lifecycle, Runner execution) {
    var context = slot.context();
    if (admissions != null) admissions.reserveKnownSession(context);
    var admitted = beforeExecution.getOrDefault(context.runId(), () -> {});
    agentSlots.put(context.runId(), slot);
    String key =
        context.projectId() == null ? context.projectRoot().toString() : context.projectId();
    try {
      boolean first =
          projectQueue.enqueue(
              key,
              context.runId(),
              ProjectRunQueue.Access.valueOf(context.mode().name()),
              () -> {
                try {
                  if (admissions != null) admissions.startExecution(context);
                  admitted.run();
                  lifecycle.accept(() -> execution.execute(context, slot.prompt(), slot.queue()));
                } finally {
                  agentSlots.remove(context.runId());
                  forget(slot);
                }
              },
              () -> {
                active.put(context.runId(), ActiveRun.of(context, slot.prompt()));
                changed();
              },
              () -> {
                if (admissions != null)
                  admissions.transition(
                      context.runId(), dev.mikoto2000.rei.application.run.RunStatus.CANCELLED);
                agentSlots.remove(context.runId());
                forget(slot);
              },
              error -> {
                if (admissions != null)
                  admissions.transition(
                      context.runId(), dev.mikoto2000.rei.application.run.RunStatus.FAILED);
                if (requestCaptures != null) requestCaptures.startFailed(context.runId());
                try {
                  lifecycle.accept(
                      () -> {
                        throw error;
                      });
                } finally {
                  agentSlots.remove(context.runId());
                  forget(slot);
                }
              },
              context.requestSource() == AgentRunContext.RequestSource.MCP);
      return first ? Disposition.STARTED : Disposition.QUEUED;
    } catch (RuntimeException error) {
      if (admissions != null)
        admissions.transition(context.runId(), dev.mikoto2000.rei.application.run.RunStatus.FAILED);
      agentSlots.remove(context.runId());
      forget(slot);
      throw error;
    }
  }

  /** 会話の実行に関する記憶の削除を処理する。 */
  private void forget(Slot slot) {
    if (admissions != null) admissions.release(slot.context().runId());
    active.remove(slot.context().runId());
    synchronized (mailboxes) {
      mailboxes.remove(slot.context().conversationId(), slot);
    }
    for (var observer : cleanupObservers)
      try {
        observer.accept(slot.context());
      } catch (RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger(ConversationInputRouter.class)
            .warn("Run cleanup observer unavailable: {}", failure.getClass().getSimpleName());
      }
    changed();
  }

  /** 会話の実行に関する変更済みを処理する。 */
  private void changed() {
    for (var listener : listeners)
      try {
        listener.run();
      } catch (RuntimeException error) {
        org.slf4j.LoggerFactory.getLogger(ConversationInputRouter.class)
            .warn("Active run display listener failed", error);
      }
  }
}
