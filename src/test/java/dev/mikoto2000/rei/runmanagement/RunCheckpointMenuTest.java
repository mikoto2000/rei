package dev.mikoto2000.rei.runmanagement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunCheckpointMenuTest {
  @Test
  void confirmationRefreshesBothViews() throws Exception {
    var access = new Fixture();
    var output = new ArrayList<String>();
    var answers = new ArrayDeque<>(List.of("1", "1", "1", "0", "q"));
    RunCheckpointMenu.open(access, prompt -> answers.poll(), output::add);
    assertEquals(1, access.discards);
    assertEquals(2, access.lists);
    assertEquals(2, access.details);
    assertTrue(output.contains("このタスクをチェックポイントから再開できなくなります"));
  }

  @Test
  void incorrectInputBackCancelAndEofNeverDiscard() throws Exception {
    for (var inputs :
        List.of(
            List.of("1", "1", "invalid"),
            List.of("1", "1", "0", "0", "q"),
            List.of("1", "1", "q"),
            List.of("1", "1"),
            List.of("0"))) {
      var access = new Fixture();
      var answers = new ArrayDeque<>(inputs);
      RunCheckpointMenu.open(access, prompt -> answers.poll(), text -> {});
      assertEquals(0, access.discards, inputs.toString());
    }
  }

  private static final class Fixture implements RunCheckpointMenu.Access {
    int discards;
    int lists;
    int details;

    @Override
    public List<RunCheckpointMenu.Run> runs() {
      lists++;
      return List.of(
          new RunCheckpointMenu.Run("p", "Project", "r", "Request", "FAILED", null, "reason"));
    }

    @Override
    public List<RunCheckpointMenu.Checkpoint> checkpoints(RunCheckpointMenu.Run run) {
      details++;
      return List.of(
          new RunCheckpointMenu.Checkpoint(
              "task", "r", "r", "2026-11-16T00:00:00Z", "FAILED", 2, "Request", discards == 0));
    }

    @Override
    public void discard(RunCheckpointMenu.Run run, RunCheckpointMenu.Checkpoint checkpoint) {
      assertEquals("task", checkpoint.taskId());
      assertEquals(2L, checkpoint.revision());
      discards++;
    }
  }
}
