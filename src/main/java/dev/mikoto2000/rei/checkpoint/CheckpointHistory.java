package dev.mikoto2000.rei.checkpoint;

/** Global checkpoint snapshot accounting. Event keys are retained for replay deduplication. */
public final class CheckpointHistory {
  private CheckpointHistory() {}
  public record Usage(long revisions,long eventKeys,long snapshotBytes,long maxRevisions,long maxBytes) {}
  public record Cleanup(long removedRevisions,long removedBytes,long protectedTasks,
      Usage before,Usage after,boolean applied,long protectedRevisions,long protectedBytes) {
    public Cleanup(long removedRevisions,long removedBytes,long protectedTasks,
        Usage before,Usage after,boolean applied) {
      this(removedRevisions,removedBytes,protectedTasks,before,after,applied,-1,-1);
    }
  }
}
