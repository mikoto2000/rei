package dev.mikoto2000.rei.core.chat;

import java.util.*;
import java.util.concurrent.Executor;

/** Runs work outside the monitor; a project slot is released only after the work's finally returns. */
public final class ProjectRunQueue {
  private static final class Job {
    final String projectId, runId;
    final Runnable work;
    final Runnable scheduled;
    boolean running;
    Job(String projectId, String runId, Runnable work, Runnable scheduled) {
      this.projectId = projectId; this.runId = runId; this.work = work; this.scheduled = scheduled;
    }
  }
  private final Map<String, ArrayDeque<Job>> projects = new HashMap<>();
  private final Executor executor;
  public ProjectRunQueue(Executor executor) { this.executor = executor; }

  public boolean enqueue(String projectId, String runId, Runnable work) {
    return enqueue(projectId, runId, work, () -> {});
  }
  public boolean enqueue(String projectId, String runId, Runnable work, Runnable scheduled) {
    var job = new Job(projectId, runId, work, scheduled);
    boolean first;
    synchronized (this) {
      var queue = projects.computeIfAbsent(projectId, ignored -> new ArrayDeque<>());
      first = queue.isEmpty();
      queue.addLast(job);
    }
    if (first) dispatch(job);
    return first;
  }
  public boolean cancelQueued(String runId) {
    Job removed = null, next = null;
    synchronized (this) {
      for (var queue : projects.values()) {
        for (var job : queue) {
          if (job.runId.equals(runId) && !job.running) { removed = job; break; }
        }
        if (removed != null) {
          boolean first = queue.peekFirst() == removed;
          queue.remove(removed);
          if (first) next = queue.peekFirst();
          break;
        }
      }
      if (removed != null && projects.get(removed.projectId).isEmpty()) projects.remove(removed.projectId);
    }
    if (next != null) dispatch(next);
    return removed != null;
  }
  private void dispatch(Job job) {
    try {
      job.scheduled.run();
      executor.execute(() -> {
        synchronized (this) {
          var queue = projects.get(job.projectId);
          if (queue == null || queue.peekFirst() != job || job.running) return;
          job.running = true;
        }
        try { job.work.run(); }
        finally { complete(job); }
      });
    } catch (RuntimeException error) {
      complete(job);
      throw error;
    }
  }
  private void complete(Job job) {
    Job next;
    synchronized (this) {
      var queue = projects.get(job.projectId);
      if (queue == null || queue.peekFirst() != job) return;
      queue.removeFirst();
      next = queue.peekFirst();
      if (next == null) projects.remove(job.projectId);
    }
    if (next != null) dispatch(next);
  }
}
