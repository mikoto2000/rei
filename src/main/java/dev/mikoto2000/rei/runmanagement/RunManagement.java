package dev.mikoto2000.rei.runmanagement;

import dev.mikoto2000.rei.runmanagement.RunCheckpointMenu.Checkpoint;
import dev.mikoto2000.rei.runmanagement.RunCheckpointMenu.Run;
import java.util.List;

/** Management operations shared by the in-process Shell and the server endpoint. */
public interface RunManagement extends RunCheckpointMenu.Access {
  @Override
  List<Run> runs();

  List<Checkpoint> checkpoints(String project, String run);

  void discard(String project, String run, String task, long revision);

  @Override
  default List<Checkpoint> checkpoints(Run run) {
    return run.projectId() == null ? List.of() : checkpoints(run.projectId(), run.runId());
  }

  @Override
  default void discard(Run run, Checkpoint checkpoint) {
    discard(run.projectId(), run.runId(), checkpoint.taskId(), checkpoint.revision());
  }
}
