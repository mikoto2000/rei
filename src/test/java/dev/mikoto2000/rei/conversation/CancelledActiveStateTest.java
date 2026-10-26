package dev.mikoto2000.rei.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.actionplan.ActionPlan;
import dev.mikoto2000.rei.core.actionplan.ActionPlanAdvisor;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.checkpoint.CheckpointAdvisor;
import dev.mikoto2000.rei.core.checkpoint.CheckpointStore;
import dev.mikoto2000.rei.core.checkpoint.TurnCheckpoint;
import dev.mikoto2000.rei.core.taskstate.TaskState;
import dev.mikoto2000.rei.core.taskstate.TaskStateAdvisor;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

class CancelledActiveStateTest {
  @Test
  void oldTaskPlanAndResumeHintAreRetainedButNotInjectedIntoLaterRuns() {
    var turns = ConversationTurnStore.inMemory();
    var old = new AgentRunContext("old", "chat", Path.of("."));
    var current = new AgentRunContext("current", "chat", Path.of("."));
    var task = new TaskState();
    var plan = new ActionPlan();
    var checkpoint = new CheckpointStore();
    try (var scope = AgentRunScope.open(old)) {
      task.start("abandoned goal");
      task.addPending("abandoned pending");
      plan.addStep("abandoned step");
      checkpoint.save(
          new TurnCheckpoint(
              "task",
              "abandoned goal",
              null,
              List.of(),
              List.of(),
              List.of("changed-file.txt"),
              null,
              null,
              "abandoned resume hint",
              "test",
              "now"));
    }
    turns.start(old, "abandoned goal");
    turns.finish(old, ConversationTurnStore.Status.CANCELLED);
    turns.start(current, "hello");
    var policy = turns.promptHistory("chat");
    var input =
        ChatClientRequest.builder()
            .prompt(new Prompt(new UserMessage("hello")))
            .context(Map.of(dev.mikoto2000.rei.context.CancelledTaskHistory.KEY, policy))
            .build();
    var projected = new TaskStateAdvisor(task).before(input, null);
    projected = new ActionPlanAdvisor(plan).before(projected, null);
    projected = new CheckpointAdvisor(checkpoint).before(projected, null);
    assertThat(projected.prompt().getContents()).isEqualTo("hello");
    assertThat(task.pending()).containsExactly("abandoned pending");
    assertThat(plan.steps()).hasSize(1);
    assertThat(checkpoint.latest()).isPresent();
    try (var scope = AgentRunScope.open(current)) {
      task.reset();
      task.start("new user task");
      plan.restore(List.of());
      plan.addStep("new user step");
    }
    projected = new TaskStateAdvisor(task).before(input, null);
    projected = new ActionPlanAdvisor(plan).before(projected, null);
    projected = new CheckpointAdvisor(checkpoint).before(projected, null);
    assertThat(projected.prompt().getContents())
        .contains("new user task", "new user step")
        .doesNotContain("abandoned");
  }
}
