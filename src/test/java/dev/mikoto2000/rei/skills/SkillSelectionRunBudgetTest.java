package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

/** スキル選択内容実行予算の結果と状態の契約を検証するテスト。 */
class SkillSelectionRunBudgetTest {
  /** スキルの利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void semanticEmbeddingBudgetFlowsThroughSelectionAndCannotFallBackIntoSelector() {
    var provider = mock(org.springframework.ai.embedding.EmbeddingModel.class);
    when(provider.call(any(org.springframework.ai.embedding.EmbeddingRequest.class)))
        .thenReturn(
            new org.springframework.ai.embedding.EmbeddingResponse(
                List.of(new org.springframework.ai.embedding.Embedding(new float[] {1, 0}, 0)),
                new org.springframework.ai.embedding.EmbeddingResponseMetadata(
                    "test", new org.springframework.ai.chat.metadata.DefaultUsage(3, 0))));
    var model = new dev.mikoto2000.rei.llm.BudgetedEmbeddingModel(provider);
    try (var client =
        new SkillEmbeddingClient(() -> model::embed, java.time.Duration.ofSeconds(2))) {
      var candidates = new SkillCandidateSelector();
      candidates.semanticSearch(
          new SemanticSkillSearch(
              new SemanticSkillProperties(true, 64, .55, 30),
              () -> client,
              () -> null,
              System::nanoTime));
      var repository = new InMemoryAgentSkillRepository(List.of(skill));
      var implicit = mock(AgentSkillImplicitSelection.class);
      var props = new AgentSkillsProperties();
      props.setEnabled(true);
      props.setMaxSelected(1);
      var selection =
          new AgentSkillSelectionService(
              props, new AgentSkillExplicitSelector(repository), implicit, repository, candidates);
      var budget = new OutputLimitRunBudget(0, 10, null, 5);
      var run = context(budget);
      assertThatThrownBy(() -> selection.select("sample", run.modelCallBudget()))
          .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
      assertThat(budget.totalTokens()).isEqualTo(6);
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(implicit);
      assertThatThrownBy(() -> selection.select("sample", run.modelCallBudget()))
          .hasMessageContaining("TOKEN_BUDGET_EXCEEDED");
      verify(provider, times(2)).call(any(org.springframework.ai.embedding.EmbeddingRequest.class));
      assertThat(dev.mikoto2000.rei.llm.ModelCallBudgetScope.current()).isNull();
    }
  }

  /** 検証に使うスキル。 */
  private final AgentSkill skill =
      new AgentSkill(
          "sample",
          "description",
          true,
          Path.of("sample"),
          Path.of("sample/SKILL.md"),
          "instructions");

  /** 検証で使用する文脈を、指定された条件に合わせて用意する。 */
  private RunExecutionContext context(OutputLimitRunBudget budget) {
    return new RunExecutionContext("run", budget, null, null, null);
  }

  /** スキルの利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void advisorPassesRunBudgetAndExplicitSelectionNeedsNoHelperCall() {
    var model = mock(ChatModel.class);
    when(model.call(org.mockito.Mockito.any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(
            new org.springframework.ai.chat.model.ChatResponse(
                List.of(
                    new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(
                            "[\"sample\"]")))));
    var repository = new InMemoryAgentSkillRepository(List.of(skill));
    var properties = new AgentSkillsProperties();
    properties.setEnabled(true);
    properties.setMaxSelected(1);
    var service =
        new AgentSkillSelectionService(
            properties,
            new AgentSkillExplicitSelector(repository),
            new AgentSkillImplicitSelector(model, repository));
    var advisor = new AgentSkillAdvisor(service, new AgentSkillPromptRenderer());
    var budget = new OutputLimitRunBudget(0, 1);
    var run = context(budget);
    var options =
        org.springframework.ai.model.tool.ToolCallingChatOptions.builder()
            .toolContext(java.util.Map.of(RunExecutionContext.KEY, run))
            .build();
    var explicit =
        org.springframework.ai.chat.client.ChatClientRequest.builder()
            .prompt(new org.springframework.ai.chat.prompt.Prompt("@skill:sample request", options))
            .build();
    assertThat(advisor.before(explicit, null).prompt().getUserMessage().getText())
        .contains("## Skill: sample");
    assertThat(budget.remainingLlmCalls()).isEqualTo(1);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(model);
    var implicit =
        explicit
            .mutate()
            .prompt(new org.springframework.ai.chat.prompt.Prompt("request", options))
            .build();
    assertThat(advisor.before(implicit, null).prompt().getUserMessage().getText())
        .contains("## Skill: sample");
    assertThat(budget.remainingLlmCalls()).isZero();
    assertThatThrownBy(() -> advisor.before(implicit, null))
        .isInstanceOf(ExecutionStoppedException.class);
    verify(model, times(1))
        .call(org.mockito.Mockito.any(org.springframework.ai.chat.prompt.Prompt.class));
  }

  /** スキルの利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancelledRunStopsBeforeModelInvocation() {
    var model = mock(ChatModel.class);
    var run = context(new OutputLimitRunBudget(0, 1));
    run.cancel();
    var selector =
        new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));
    assertThatThrownBy(
            () -> selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall))
        .isInstanceOf(java.util.concurrent.CancellationException.class);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(model);
  }

  /** スキルの利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void helperConsumesSharedBudgetAndStopsBeforeSecondModelCall() {
    var model = mock(ChatModel.class);
    when(model.call(org.mockito.Mockito.any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(
            new org.springframework.ai.chat.model.ChatResponse(
                List.of(
                    new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage(
                            "[\"sample\"]")))));
    var parent =
        new OutputLimitRunBudget.LlmCallReservation() {
          /** 検証に使う残量。 */
          int remaining = 1;

          /** {@code tryReserve} の条件を再現するための補助情報を返す。 */
          public boolean tryReserve() {
            if (remaining == 0) return false;
            remaining--;
            return true;
          }

          /** {@code remaining} の条件を再現するための補助情報を返す。 */
          public int remaining() {
            return remaining;
          }
        };
    var run = context(new OutputLimitRunBudget(0, 10, parent));
    var selector =
        new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));
    assertThat(selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall))
        .containsExactly(skill);
    assertThat(parent.remaining()).isZero();
    assertThatThrownBy(
            () -> selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall))
        .isInstanceOf(ExecutionStoppedException.class);
    verify(model, times(1))
        .call(org.mockito.Mockito.any(org.springframework.ai.chat.prompt.Prompt.class));
  }

  /** スキルの利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void emptyCandidatesDoNotConsumeAndFailedAttemptIsStillCharged() {
    var model = mock(ChatModel.class);
    when(model.call(org.mockito.Mockito.any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenThrow(new IllegalStateException("provider failed"));
    var budget = new OutputLimitRunBudget(0, 1);
    var run = context(budget);
    var selector =
        new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));
    assertThat(selector.select("request", Set.of(), List.of(), run::consumeNextLlmCall)).isEmpty();
    assertThat(budget.remainingLlmCalls()).isEqualTo(1);
    assertThat(selector.select("request", Set.of(), List.of(skill), run::consumeNextLlmCall))
        .isEmpty();
    assertThat(budget.remainingLlmCalls()).isZero();
    verify(model, times(1))
        .call(org.mockito.Mockito.any(org.springframework.ai.chat.prompt.Prompt.class));
  }
}
