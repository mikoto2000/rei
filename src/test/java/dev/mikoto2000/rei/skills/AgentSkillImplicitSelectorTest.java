package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;

/** エージェントスキル暗黙の対象の選択条件の結果と状態の契約を検証するテスト。 */
class AgentSkillImplicitSelectorTest {

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sendsOnlySkillMetadataAndUserRequestOnNormalPath() {
    AgentSkill skill = skill("skill-a");
    ChatModel model = Mockito.mock(ChatModel.class);
    when(model.call(anyString())).thenReturn("[\"skill-a\"]");
    var selector =
        new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));

    assertThat(selector.select("please use it", Set.of(), null)).containsExactly(skill);

    var prompt = org.mockito.ArgumentCaptor.forClass(String.class);
    Mockito.verify(model).call(prompt.capture());
    assertSelectionPrompt(prompt.getValue(), skill);
    assertThat(prompt.getValue().indexOf("Skills:"))
        .isLessThan(prompt.getValue().indexOf("User request:"));
    assertThat(new AgentSkillPromptRenderer().render("please use it", List.of(skill)))
        .contains(skill.instructions());
  }

  /** スキルの利用で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void sendsOnlySkillMetadataAndUserRequestWithTokenBudget() {
    AgentSkill skill = skill("skill-a");
    ChatModel model = Mockito.mock(ChatModel.class);
    var response =
        new org.springframework.ai.chat.model.ChatResponse(
            List.of(
                new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("[\"skill-a\"]"))));
    when(model.call(Mockito.any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(response);
    var budget = Mockito.mock(dev.mikoto2000.rei.llm.ModelCallBudget.class);
    when(budget.tokenLimitEnabled()).thenReturn(true);
    var selector =
        new AgentSkillImplicitSelector(model, new InMemoryAgentSkillRepository(List.of(skill)));

    assertThat(selector.select("please use it", Set.of(), List.of(skill), budget))
        .containsExactly(skill);

    var prompt =
        org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
    Mockito.verify(model).call(prompt.capture());
    assertSelectionPrompt(prompt.getValue().getContents(), skill);
    Mockito.verify(budget).run();
    Mockito.verify(budget).recordTotalTokens(response.getMetadata().getUsage().getTotalTokens());
    assertThat(prompt.getValue().getContents().indexOf("Skills:"))
        .isLessThan(prompt.getValue().getContents().indexOf("User request:"));
    assertThat(new AgentSkillPromptRenderer().render("please use it", List.of(skill)))
        .contains(skill.instructions());
  }

  /** 複数の検証で共通する期待結果を確認し、要件に合わない状態を検出する。 */
  private void assertSelectionPrompt(String prompt, AgentSkill skill) {
    assertThat(prompt)
        .endsWith("User request:\nplease use it\n")
        .contains(
            "Skills:\n- name: "
                + skill.name()
                + "\n  description: "
                + skill.description()
                + "\n\nReturn format:\n")
        .doesNotContain("excerpt:", skill.instructions());
  }

  /** スキルの利用の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void wrappedInterruptionTerminatesSelectionAndRestoresInterrupt() {
    var model = Mockito.mock(ChatModel.class);
    when(model.call(anyString()))
        .thenThrow(new RuntimeException(new InterruptedException("cancelled")));
    var selector =
        new AgentSkillImplicitSelector(
            model, new InMemoryAgentSkillRepository(List.of(skill("a"))));
    try {
      org.assertj.core.api.Assertions.assertThatThrownBy(
              () -> selector.select("request", Set.of(), List.of(skill("a"))))
          .isInstanceOf(java.util.concurrent.CancellationException.class);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  /** スキルの利用で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void selectsSkillsFromLlmJsonArray() {
    AgentSkill skill = skill("skill-a");
    AgentSkillImplicitSelector selector = selector("[\"skill-a\"]", skill);

    List<AgentSkill> selected = selector.select("please use it", Set.of(), List.of(skill));

    assertThat(selected).containsExactly(skill);
  }

  /** 空のを返すことを確認する。 */
  @Test
  void returnsEmptyWhenLlmReturnsEmptyArray() {
    AgentSkill skill = skill("skill-a");
    AgentSkillImplicitSelector selector = selector("[]", skill);

    assertThat(selector.select("general chat", Set.of(), List.of(skill))).isEmpty();
  }

  /** スキルの利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void ignoresUnknownSkillNames() {
    AgentSkill skill = skill("skill-a");
    AgentSkillImplicitSelector selector = selector("[\"missing\"]", skill);

    assertThat(selector.select("please use it", Set.of(), List.of(skill))).isEmpty();
  }

  /** 空のを返すことを確認する。 */
  @Test
  void returnsEmptyWhenJsonParsingFails() {
    AgentSkill skill = skill("skill-a");
    AgentSkillImplicitSelector selector = selector("not json", skill);

    assertThat(selector.select("please use it", Set.of(), List.of(skill))).isEmpty();
  }

  /** スキルの利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void cannotSelectSkillOutsideProvidedCandidates() {
    AgentSkill included = skill("included");
    AgentSkill excluded = skill("excluded");
    AgentSkillImplicitSelector selector = selector("[\"excluded\"]", included, excluded);

    assertThat(selector.select("request", Set.of(), List.of(included))).isEmpty();
  }

  /** {@code selector} の条件を再現するための補助情報を返す。 */
  private AgentSkillImplicitSelector selector(String llmResponse, AgentSkill... skills) {
    ChatModel chatModel = Mockito.mock(ChatModel.class);
    when(chatModel.call(anyString())).thenReturn(llmResponse);
    return new AgentSkillImplicitSelector(
        chatModel, new InMemoryAgentSkillRepository(List.of(skills)));
  }

  /** {@code skill} の条件を再現するための補助情報を返す。 */
  private AgentSkill skill(String name) {
    return new AgentSkill(
        name,
        name + " description",
        true,
        Path.of(name),
        Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
