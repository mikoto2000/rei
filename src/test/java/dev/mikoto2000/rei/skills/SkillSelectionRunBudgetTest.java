package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyString;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

class SkillSelectionRunBudgetTest {
  @Test void semanticEmbeddingBudgetFlowsThroughSelectionAndCannotFallBackIntoSelector() {
    var provider=mock(org.springframework.ai.embedding.EmbeddingModel.class);
    when(provider.call(any(org.springframework.ai.embedding.EmbeddingRequest.class))).thenReturn(new org.springframework.ai.embedding.EmbeddingResponse(
        List.of(new org.springframework.ai.embedding.Embedding(new float[]{1,0},0)),
        new org.springframework.ai.embedding.EmbeddingResponseMetadata("test",new org.springframework.ai.chat.metadata.DefaultUsage(3,0))));
    var model=new dev.mikoto2000.rei.llm.BudgetedEmbeddingModel(provider);
    try(var client=new SkillEmbeddingClient(()->model::embed,java.time.Duration.ofSeconds(2))) {
      var candidates=new SkillCandidateSelector();candidates.semanticSearch(new SemanticSkillSearch(new SemanticSkillProperties(true,64,.55,30),
          ()->client,()->null,System::nanoTime));
      var repository=new InMemoryAgentSkillRepository(List.of(skill));var implicit=mock(AgentSkillImplicitSelection.class);
      var props=new AgentSkillsProperties();props.setEnabled(true);props.setMaxSelected(1);
      var selection=new AgentSkillSelectionService(props,new AgentSkillExplicitSelector(repository),implicit,repository,candidates);
      var budget=new OutputLimitRunBudget(0,10,null,5);var run=context(budget);
      assertThatThrownBy(()->selection.select("sample",run.modelCallBudget())).hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
      assertThat(budget.totalTokens()).isEqualTo(6);verifyNoInteractions(implicit);
      assertThatThrownBy(()->selection.select("sample",run.modelCallBudget())).hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
      verify(provider,times(2)).call(any(org.springframework.ai.embedding.EmbeddingRequest.class));
      assertThat(dev.mikoto2000.rei.llm.ModelCallBudgetScope.current()).isNull();
    }
  }
  private final AgentSkill skill = new AgentSkill("sample", "description", true,
      Path.of("sample"), Path.of("sample/SKILL.md"), "instructions");

  private RunExecutionContext context(OutputLimitRunBudget budget) {
    return new RunExecutionContext("run", budget, null, null, null);
  }

  @Test void advisorPassesRunBudgetAndExplicitSelectionNeedsNoHelperCall() {
    var model = mock(ChatModel.class);
    when(model.call(anyString())).thenReturn("[\"sample\"]");
    var repository = new InMemoryAgentSkillRepository(List.of(skill));
    var properties = new AgentSkillsProperties();
    properties.setEnabled(true);
    properties.setMaxSelected(1);
    var service = new AgentSkillSelectionService(properties, new AgentSkillExplicitSelector(repository),
        new AgentSkillImplicitSelector(model, repository));
    var advisor = new AgentSkillAdvisor(service, new AgentSkillPromptRenderer());
    var budget = new OutputLimitRunBudget(0, 1);
    var run = context(budget);
    var options = org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
        .toolContext(java.util.Map.of(RunExecutionContext.KEY, run)).build();
    var explicit = org.springframework.ai.chat.client.ChatClientRequest.builder()
        .prompt(new org.springframework.ai.chat.prompt.Prompt("@skill:sample request", options)).build();
    assertThat(advisor.before(explicit, null).prompt().getUserMessage().getText()).contains("## Skill: sample");
    assertThat(budget.remainingLlmCalls()).isEqualTo(1);
    verifyNoInteractions(model);
    var implicit = explicit.mutate().prompt(new org.springframework.ai.chat.prompt.Prompt("request", options)).build();
    assertThat(advisor.before(implicit, null).prompt().getUserMessage().getText()).contains("## Skill: sample");
    assertThat(budget.remainingLlmCalls()).isZero();
    assertThatThrownBy(() -> advisor.before(implicit, null)).isInstanceOf(ExecutionStoppedException.class);
    verify(model, times(1)).call(anyString());
  }

  @Test void cancelledRunStopsBeforeModelInvocation() {
    var model = mock(ChatModel.class);
    var run = context(new OutputLimitRunBudget(0, 1));
    run.cancel();
    var selector = new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));
    assertThatThrownBy(() -> selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall))
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    verifyNoInteractions(model);
  }

  @Test void helperConsumesSharedBudgetAndStopsBeforeSecondModelCall() {
    var model = mock(ChatModel.class);
    when(model.call(anyString())).thenReturn("[\"sample\"]");
    var parent = new OutputLimitRunBudget.LlmCallReservation() {
      int remaining = 1;
      public boolean tryReserve() { if (remaining == 0) return false; remaining--; return true; }
      public int remaining() { return remaining; }
    };
    var run = context(new OutputLimitRunBudget(0, 10, parent));
    var selector = new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));
    assertThat(selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall)).containsExactly(skill);
    assertThat(parent.remaining()).isZero();
    assertThatThrownBy(() -> selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall))
        .isInstanceOf(ExecutionStoppedException.class);
    verify(model, times(1)).call(anyString());
  }

  @Test void emptyCandidatesDoNotConsumeAndFailedAttemptIsStillCharged() {
    var model = mock(ChatModel.class);
    when(model.call(anyString())).thenThrow(new IllegalStateException("provider failed"));
    var budget = new OutputLimitRunBudget(0, 1);
    var run = context(budget);
    var selector = new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));
    assertThat(selector.select("request", Set.of(), List.of(), run::consumeNextLlmCall)).isEmpty();
    assertThat(budget.remainingLlmCalls()).isEqualTo(1);
    assertThat(selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall)).isEmpty();
    assertThat(budget.remainingLlmCalls()).isZero();
    verify(model, times(1)).call(anyString());
  }
}
