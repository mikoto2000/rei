package dev.mikoto2000.rei.core.chat;

import java.util.*;
import java.util.concurrent.Executor;

/** Runs work outside the monitor; a project slot is released only after the work's finally returns. */
public final class ProjectRunQueue {
  public enum Access { EXCLUSIVE, READ_ONLY, CONVERSATION }
  public static final class CapacityExceededException extends java.util.concurrent.RejectedExecutionException {
    public CapacityExceededException() { super("Run admission capacity reached"); }
  }
  private static final class Job {
    final String projectId, runId;
    final Access access;
    final Runnable work;
    final Runnable scheduled;
    final Runnable discarded;
    final java.util.function.Consumer<RuntimeException> rejected;
    boolean dispatched, running;
    Job(String projectId, String runId, Access access, Runnable work, Runnable scheduled, Runnable discarded,
        java.util.function.Consumer<RuntimeException> rejected) {
      this.projectId = projectId; this.runId = runId; this.access = access; this.work = work; this.scheduled = scheduled;
      this.discarded = discarded; this.rejected = rejected;
    }
  }
  private final Map<String, ArrayDeque<Job>> projects = new LinkedHashMap<>();
  private final Executor executor;
  private final int projectLimit;
  private final int totalLimit;
  private final int concurrency;
  /** Bounds include executing jobs so cancellation does not release a running write prematurely. */
  public ProjectRunQueue(Executor executor) { this(executor, 64, 256); }
  public ProjectRunQueue(Executor executor, int projectLimit, int totalLimit) {
    this(executor, projectLimit, totalLimit, 64);
  }
  public ProjectRunQueue(Executor executor, int projectLimit, int totalLimit, int concurrency) {
    if (projectLimit < 1 || totalLimit < projectLimit)
      throw new IllegalArgumentException("Run admission limits must be positive and project <= total");
    this.executor = Objects.requireNonNull(executor);
    this.projectLimit = projectLimit;
    this.totalLimit = totalLimit;
    if (concurrency < 1 || concurrency > 64) throw new IllegalArgumentException("Concurrency must be 1..64");
    this.concurrency = concurrency;
  }

  public boolean enqueue(String projectId, String runId, Runnable work) {
    return enqueue(projectId, runId, work, () -> {});
  }
  public boolean enqueue(String projectId, String runId, Runnable work, Runnable scheduled) {
    return enqueue(projectId, runId, work, scheduled, () -> {}, error -> {});
  }
  public boolean enqueue(String projectId, String runId, Runnable work, Runnable scheduled, Runnable discarded,
      java.util.function.Consumer<RuntimeException> rejected) {
    return enqueue(projectId, runId, Access.EXCLUSIVE, work, scheduled, discarded, rejected);
  }
  public boolean enqueue(String projectId, String runId, Access access, Runnable work) {
    return enqueue(projectId, runId, access, work, () -> {}, () -> {}, error -> {});
  }
  public boolean enqueue(String projectId, String runId, Access access, Runnable work, Runnable scheduled,
      Runnable discarded, java.util.function.Consumer<RuntimeException> rejected) {
    var job = new Job(projectId, runId, Objects.requireNonNull(access), work, scheduled, discarded, rejected);
    List<Job> dispatch;
    boolean started;
    synchronized (this) {
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
  /** Includes queued, dispatching and executing operations until their finally releases the slot. */
  public synchronized boolean containsRun(String projectId,String runId) {
    var queue=projects.get(projectId);
    return queue!=null&&queue.stream().anyMatch(job->job.runId.equals(runId));
  }
  public boolean cancelQueued(String runId) {
    Job removed = null;
    List<Job> dispatch;
    synchronized (this) {
      for (var queue : projects.values()) {
        for (var job : queue) {
          if (job.runId.equals(runId) && !job.running) { removed = job; break; }
        }
        if (removed != null) {
          queue.remove(removed);
          break;
        }
      }
      if (removed != null && projects.get(removed.projectId).isEmpty()) projects.remove(removed.projectId);
      dispatch = reserve();
    }
    dispatchAll(dispatch, null);
    return removed != null;
  }
  /** Called under the monitor; reservation prevents another admission from dispatching the same job. */
  private List<Job> reserve() {
    int remaining = concurrency - projects.values().stream().flatMap(Collection::stream)
        .mapToInt(job -> job.dispatched ? 1 : 0).sum();
    var selected = new ArrayList<Job>();
    for (var queue : projects.values()) for (var job : queue) {
      if (remaining == 0) return selected;
      if (!job.dispatched && eligible(queue, job)) {
        job.dispatched = true; selected.add(job); remaining--;
      }
    }
    return selected;
  }
  private boolean eligible(ArrayDeque<Job> queue, Job candidate) {
    if (candidate.access == Access.CONVERSATION) return true;
    for (var earlier : queue) {
      if (earlier == candidate) break;
      if (earlier.access == Access.CONVERSATION) continue;
      if (candidate.access == Access.EXCLUSIVE || earlier.access == Access.EXCLUSIVE) return false;
    }
    return queue.stream().noneMatch(other -> other != candidate && other.dispatched
        && other.access != Access.CONVERSATION
        && (candidate.access == Access.EXCLUSIVE || other.access == Access.EXCLUSIVE));
  }
  private void dispatchAll(List<Job> selected, Job submitting) {
    RuntimeException failure = null;
    for (var job : selected) try { dispatch(job); }
    catch (RuntimeException error) {
      if (job == submitting || submitting==null && failure==null) failure = error;
      else org.slf4j.LoggerFactory.getLogger(ProjectRunQueue.class).warn("Queued Run dispatch rejected: {}", error.getClass().getSimpleName());
    }
    if (failure != null) throw failure;
  }
  private void dispatch(Job job) {
    try {
      if (!isReserved(job)) { job.discarded.run(); return; }
      job.scheduled.run();
      if (!isReserved(job)) { job.discarded.run(); return; }
      executor.execute(() -> {
        boolean start;
        synchronized (this) {
          var queue = projects.get(job.projectId);
          start = queue != null && queue.contains(job) && job.dispatched && !job.running;
          if (start) job.running = true;
        }
        if (!start) { job.discarded.run(); return; }
        try { job.work.run(); }
        finally { complete(job); }
      });
    } catch (RuntimeException error) {
      try { if (!job.running) job.rejected.accept(error); }
      finally { complete(job); }
      throw error;
    }
  }
  private synchronized boolean isReserved(Job job) {
    var queue = projects.get(job.projectId);
    return queue != null && queue.contains(job) && job.dispatched && !job.running;
  }
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
