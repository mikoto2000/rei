package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.stagnation.ProgressEvaluator;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** 保存会話の復元がスキル選択と予算に与える影響を確認する。 */
class RestoredSkillRoutingTest {
  /** 復元履歴を使う続行では再選択せず、履歴を使い切った後の新しい入力は選択する。 */
  @Test
  void restoredApprovalPreservesSkillHistoryPendingToolAndBudget() {
    var selection = mock(AgentSkillSelectionService.class);
    when(selection.select(anyString(), any()))
        .thenThrow(new AssertionError("Unnecessary selection"));
    var advisor = new AgentSkillAdvisor(selection, new AgentSkillPromptRenderer());
    var run = context();
    List<Message> history = List.of(new UserMessage("## Skill: review\nSaved review instructions"));
    var pending =
        new RunContinuation.PendingTools(
            "",
            List.of(new AssistantMessage.ToolCall("saved", "function", "writeMultiFile", "{}")),
            List.of());
    var budget = new OutputLimitRunBudget.Snapshot(1, 372, 4000, false, 2, 1);
    run.resume(new RunContinuation.Saved("resume", history, budget, pending));
    var request = request(run, "resume");

    assertThat(advisor.before(request, null)).isSameAs(request);
    assertThat(advisor.before(request, null)).isSameAs(request);
    verifyNoInteractions(selection);
    assertThat(run.budgetSnapshot()).isEqualTo(budget);
    assertThat(run.takeResumeMessages()).containsExactlyElementsOf(history);
    assertThat(run.takePendingApprovalTools()).isSameAs(pending);
    assertThat(run.takeResumeMessages()).isEmpty();

    reset(selection);
    when(selection.select(anyString(), any()))
        .thenReturn(new AgentSkillSelection(List.of(), List.of(), List.of(), "new guidance"));
    advisor.before(request(run, "new guidance"), null);
    verify(selection).select(eq("new guidance"), any());
  }

  /** 通常のチェックポイント再開には保存会話の続行を混同しない。 */
  @Test
  void checkpointRestorationWithoutSavedConversationStillSelectsSkills() {
    var selection = mock(AgentSkillSelectionService.class);
    when(selection.select(anyString(), any()))
        .thenReturn(new AgentSkillSelection(List.of(), List.of(), List.of(), "resume"));
    var run = context();
    run.beginContextRestoration();
    new AgentSkillAdvisor(selection, new AgentSkillPromptRenderer())
        .before(request(run, "resume"), null);
    verify(selection).select(eq("resume"), any());
  }

  /** 保存履歴があっても取消の確認を省略しない。 */
  @Test
  void cancellationIsCheckedBeforeReusingSavedSkills() {
    var selection = mock(AgentSkillSelectionService.class);
    var run = context();
    run.resume(
        new RunContinuation.Saved(
            "resume", List.of(new UserMessage("saved")), run.budgetSnapshot()));
    run.cancel();
    assertThatThrownBy(
            () ->
                new AgentSkillAdvisor(selection, new AgentSkillPromptRenderer())
                    .before(request(run, "resume"), null))
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    verifyNoInteractions(selection);
  }

  /** 保存状態を扱う実行文脈を用意する。 */
  private RunExecutionContext context() {
    return new RunExecutionContext(
        "run",
        new OutputLimitRunBudget(2, 1000),
        new ProgressEvaluator(Path.of("."), null),
        new AgentEventFactory(Clock.systemUTC()),
        new InMemoryAgentEventBus());
  }

  /** 実行文脈を含むモデル要求を用意する。 */
  private ChatClientRequest request(RunExecutionContext run, String text) {
    var options =
        ToolCallingChatOptions.builder().toolContext(Map.of(RunExecutionContext.KEY, run)).build();
    return ChatClientRequest.builder().prompt(new Prompt(text, options)).build();
  }
}
