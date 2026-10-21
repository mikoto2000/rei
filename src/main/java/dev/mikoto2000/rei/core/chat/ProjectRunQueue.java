package dev.mikoto2000.rei.core.chat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * プロジェクト内の実行順序とアクセス種別を調整し、受付上限と同時実行数を管理する。
 *
 * <p>Runs work outside the monitor; a project slot is released only after the work's finally
 * returns.
 */
public final class ProjectRunQueue {
  /** 会話の実行におけるアクセスの選択肢。 */
  public enum Access {
    /** 排他。 */
    EXCLUSIVE,
    /** 参照専用。 */
    READ_ONLY,
    /** 会話。 */
    CONVERSATION
  }

  /** 会話の実行を継続できない許容件数上限超過の状況を伝える例外。 */
  public static final class CapacityExceededException
      extends java.util.concurrent.RejectedExecutionException {
    /** 既定の状態でCapacityExceededExceptionを初期化する。 */
    public CapacityExceededException() {
      super("Run admission capacity reached");
    }
  }

  /** 会話の実行に必要な実行する作業を扱う。 */
  private static final class Job {
    /** 所属プロジェクトの識別子／実行の識別子。 */
    final String projectId, runId;

    /** アクセス。 */
    final Access access;

    /** 作業。 */
    final Runnable work;

    /** 実行予約。 */
    final Runnable scheduled;

    /** 破棄時の処理。 */
    final Runnable discarded;

    /** 拒否済み。 */
    final java.util.function.Consumer<RuntimeException> rejected;

    /** 引き渡し済み／実行中／MCP。 */
    boolean dispatched, running, mcp;

    /** 指定された値や利用先を使って、Jobを初期化する。 */
    Job(
        String projectId,
        String runId,
        Access access,
        Runnable work,
        Runnable scheduled,
        Runnable discarded,
        java.util.function.Consumer<RuntimeException> rejected) {
      this.projectId = projectId;
      this.runId = runId;
      this.access = access;
      this.work = work;
      this.scheduled = scheduled;
      this.discarded = discarded;
      this.rejected = rejected;
    }
  }

  /** プロジェクトごとの受付順を保持する実行待ち。実行中の作業も解放まで保持する。 */
  private final Map<String, ArrayDeque<Job>> projects = new LinkedHashMap<>();

  /** 実行先。 */
  private final Executor executor;

  /** 一つのプロジェクトで受け付ける、実行中を含む作業数の上限。 */
  private final int projectLimit;

  /** すべてのプロジェクトで受け付ける、実行中を含む作業数の上限。 */
  private final int totalLimit;

  /** 同時に実行へ進める作業数の上限。 */
  private final int concurrency;

  /**
   * 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。
   *
   * <p>Bounds include executing jobs so cancellation does not release a running write prematurely.
   */
  public ProjectRunQueue(Executor executor) {
    this(executor, 64, 256);
  }

  /** 会話の実行を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ProjectRunQueue(Executor executor, int projectLimit, int totalLimit) {
    this(executor, projectLimit, totalLimit, 64);
  }

  /** 指定された値や利用先を使って、ProjectRunQueueを初期化する。 */
  public ProjectRunQueue(Executor executor, int projectLimit, int totalLimit, int concurrency) {
    if (projectLimit < 1 || totalLimit < projectLimit)
      throw new IllegalArgumentException(
          "Run admission limits must be positive and project <= total");
    this.executor = Objects.requireNonNull(executor);
    this.projectLimit = projectLimit;
    this.totalLimit = totalLimit;
    if (concurrency < 1 || concurrency > 64)
      throw new IllegalArgumentException("Concurrency must be 1..64");
    this.concurrency = concurrency;
  }

  /** プロジェクト別・全体の受付上限を確認し、アクセス種別に応じて実行順序を予約する。 */
  public boolean enqueue(String projectId, String runId, Runnable work) {
    return enqueue(projectId, runId, work, () -> {});
  }

  /** プロジェクト別・全体の受付上限を確認し、アクセス種別に応じて実行順序を予約する。 */
  public boolean enqueue(String projectId, String runId, Runnable work, Runnable scheduled) {
    return enqueue(projectId, runId, work, scheduled, () -> {}, error -> {});
  }

  /** プロジェクト別・全体の受付上限を確認し、アクセス種別に応じて実行順序を予約する。 */
  public boolean enqueue(
      String projectId,
      String runId,
      Runnable work,
      Runnable scheduled,
      Runnable discarded,
      java.util.function.Consumer<RuntimeException> rejected) {
    return enqueue(projectId, runId, Access.EXCLUSIVE, work, scheduled, discarded, rejected);
  }

  /** プロジェクト別・全体の受付上限を確認し、アクセス種別に応じて実行順序を予約する。 */
  public boolean enqueue(String projectId, String runId, Access access, Runnable work) {
    return enqueue(projectId, runId, access, work, () -> {}, () -> {}, error -> {});
  }

  /** プロジェクト別・全体の受付上限を確認し、アクセス種別に応じて実行順序を予約する。 */
  public boolean enqueue(
      String projectId,
      String runId,
      Access access,
      Runnable work,
      Runnable scheduled,
      Runnable discarded,
      java.util.function.Consumer<RuntimeException> rejected) {
    return enqueue(projectId, runId, access, work, scheduled, discarded, rejected, false);
  }

  /** プロジェクト別・全体の受付上限を確認し、アクセス種別に応じて実行順序を予約する。 */
  public boolean enqueue(
      String projectId,
      String runId,
      Access access,
      Runnable work,
      Runnable scheduled,
      Runnable discarded,
      java.util.function.Consumer<RuntimeException> rejected,
      boolean mcp) {
    var job =
        new Job(
            projectId, runId, Objects.requireNonNull(access), work, scheduled, discarded, rejected);
    job.mcp = mcp;
    List<Job> dispatch;
    boolean started;
    synchronized (this) {
      // 外部からのMCP要求が待機枠や実行枠を占有しないよう、専用の受付上限を適用する。
      if (mcp) {
        long pending =
            projects.values().stream()
                .flatMap(Collection::stream)
                .filter(j -> j.mcp && !j.dispatched)
                .count();
        long total =
            projects.values().stream().flatMap(Collection::stream).filter(j -> j.mcp).count();
        if (pending >= 8 || total >= 10) throw new CapacityExceededException();
      }
      // 実行中の作業も受付件数に含め、キャンセル直後に書き込み枠が二重に使われることを防ぐ。
      var existing = projects.get(projectId);
      if ((existing != null && existing.size() >= projectLimit)
          || projects.values().stream().mapToInt(ArrayDeque::size).sum() >= totalLimit)
        throw new CapacityExceededException();
      var queue = projects.computeIfAbsent(projectId, ignored -> new ArrayDeque<>());
      queue.addLast(job);
      dispatch = reserve();
      started = job.dispatched;
    }
    dispatchAll(dispatch, job);
    return started;
  }

  /**
   * 待機中から実行終了時の解放までを含め、対象の実行がキューに属するか確認する。
   *
   * <p>Includes queued, dispatching and executing operations until their finally releases the slot.
   */
  public synchronized boolean containsRun(String projectId, String runId) {
    var queue = projects.get(projectId);
    return queue != null && queue.stream().anyMatch(job -> job.runId.equals(runId));
  }

  /** 会話の実行において、待機中かどうかを判定する。 */
  public synchronized boolean isQueued(String projectId, String runId) {
    var queue = projects.get(projectId);
    return queue != null && queue.stream().anyMatch(job -> job.runId.equals(runId) && !job.running);
  }

  /** 利用者が不要とした実行を止めるため、待機中をキャンセルする。 */
  public boolean cancelQueued(String runId) {
    Job removed = null;
    List<Job> dispatch;
    synchronized (this) {
      for (var queue : projects.values()) {
        for (var job : queue) {
          if (job.runId.equals(runId) && !job.running) {
            removed = job;
            break;
          }
        }
        if (removed != null) {
          queue.remove(removed);
          break;
        }
      }
      if (removed != null && projects.get(removed.projectId).isEmpty())
        projects.remove(removed.projectId);
      dispatch = reserve();
    }
    try {
      dispatchAll(dispatch, null);
    } catch (RuntimeException error) {
      // 後続作業の受付失敗はその作業側で扱い、取り消した入力を再び実行対象に戻さない。
      org.slf4j.LoggerFactory.getLogger(ProjectRunQueue.class)
          .warn(
              "Successor dispatch rejected after queued cancellation: {}",
              error.getClass().getSimpleName());
    }
    return removed != null;
  }

  /**
   * 受付済みの作業から、実行順序・アクセス種別・同時実行数を満たす対象を予約する。
   *
   * <p>Called under the monitor; reservation prevents another admission from dispatching the same
   * job.
   */
  private List<Job> reserve() {
    int remaining =
        concurrency
            - projects.values().stream()
                .flatMap(Collection::stream)
                .mapToInt(job -> job.dispatched ? 1 : 0)
                .sum();
    var selected = new ArrayList<Job>();
    for (var queue : projects.values())
      for (var job : queue) {
        if (remaining == 0) return selected;
        if (!job.dispatched
            && eligible(queue, job)
            && (!job.mcp
                || projects.values().stream()
                        .flatMap(Collection::stream)
                        .filter(j -> j.mcp && j.dispatched)
                        .count()
                    < 2)) {
          job.dispatched = true;
          selected.add(job);
          remaining--;
        }
      }
    return selected;
  }

  /** 先行する作業と実行中の作業に競合せず、対象の作業を開始できるか判定する。 */
  private boolean eligible(ArrayDeque<Job> queue, Job candidate) {
    // 会話入力は独立に進め、その他の操作は先行する排他操作と実行中の書き込みを追い越させない。
    if (candidate.access == Access.CONVERSATION) return true;
    for (var earlier : queue) {
      if (earlier == candidate) break;
      if (earlier.access == Access.CONVERSATION) continue;
      if (candidate.access == Access.EXCLUSIVE || earlier.access == Access.EXCLUSIVE) return false;
    }
    return queue.stream()
        .noneMatch(
            other ->
                other != candidate
                    && other.dispatched
                    && other.access != Access.CONVERSATION
                    && (candidate.access == Access.EXCLUSIVE || other.access == Access.EXCLUSIVE));
  }

  /** 予約した作業を実行先へ渡し、今回の受付に関係する拒否を呼び出し元へ伝える。 */
  private void dispatchAll(List<Job> selected, Job submitting) {
    RuntimeException failure = null;
    for (var job : selected)
      try {
        dispatch(job);
      } catch (RuntimeException error) {
        if (job == submitting || submitting == null && failure == null) failure = error;
        else
          org.slf4j.LoggerFactory.getLogger(ProjectRunQueue.class)
              .warn("Queued Run dispatch rejected: {}", error.getClass().getSimpleName());
      }
    if (failure != null) throw failure;
  }

  /** 予約がまだ有効な作業だけを開始し、完了または拒否の後に実行枠を解放する。 */
  private void dispatch(Job job) {
    try {
      if (!isReserved(job)) {
        job.discarded.run();
        return;
      }
      // 実行予約の通知中にキャンセルされる可能性があるため、実行先へ渡す前に予約を確認し直す。
      job.scheduled.run();
      if (!isReserved(job)) {
        job.discarded.run();
        return;
      }
      executor.execute(
          () -> {
            boolean start;
            synchronized (this) {
              var queue = projects.get(job.projectId);
              start = queue != null && queue.contains(job) && job.dispatched && !job.running;
              if (start) job.running = true;
            }
            if (!start) {
              job.discarded.run();
              return;
            }
            try {
              job.work.run();
            } finally {
              // 作業本体の終了後に枠を解放し、次の書き込みが早過ぎる時点で始まることを防ぐ。
              complete(job);
            }
          });
    } catch (RuntimeException error) {
      try {
        if (!job.running) job.rejected.accept(error);
      } finally {
        complete(job);
      }
      throw error;
    }
  }

  /** キャンセルされず、実行開始を待つ有効な予約が残っているか確認する。 */
  private synchronized boolean isReserved(Job job) {
    var queue = projects.get(job.projectId);
    return queue != null && queue.contains(job) && job.dispatched && !job.running;
  }

  /** 作業をキューから解放し、次に実行可能になった作業を開始する。 */
  private void complete(Job job) {
    List<Job> dispatch;
    synchronized (this) {
      var queue = projects.get(job.projectId);
      if (queue == null || !queue.remove(job)) return;
      if (queue.isEmpty()) projects.remove(job.projectId);
      dispatch = reserve();
    }
    dispatchAll(dispatch, null);
  }
}
