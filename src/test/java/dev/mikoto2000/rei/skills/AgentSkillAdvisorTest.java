package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

class AgentSkillAdvisorTest {

  @Test
  void injectsSelectedSkillIntoUserMessageText() {
    AgentSkill skill = skill("sample");
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("@skill:sample hello")).thenReturn(
        new AgentSkillSelection(List.of(skill), List.of(), List.of(), "hello"));
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer());
    ChatClientRequest request = request("@skill:sample hello");

    ChatClientRequest advised = advisor.before(request, Mockito.mock(AdvisorChain.class));

    assertThat(advised.prompt().getUserMessage().getText()).contains("## Skill: sample");
    assertThat(advised.prompt().getUserMessage().getText()).contains("sample instructions");
    assertThat(advised.prompt().getUserMessage().getText()).contains("--- User request ---\nhello");
    assertThat(advised.prompt().getUserMessage().getText()).doesNotContain("@skill:sample");
    assertThat(advised.prompt().getOptions()).isSameAs(request.prompt().getOptions());
  }

  @Test
  void printsWarningsAndSelectedSkillNames() {
    AgentSkill skill = skill("sample");
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("@skill:missing hello")).thenReturn(
        new AgentSkillSelection(List.of(skill), List.of(), List.of("[warn] Skill が見つかりません: missing"), "hello"));
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      advisor.before(request("@skill:missing hello"), Mockito.mock(AdvisorChain.class));
    } finally {
      System.setOut(originalOut);
    }

    assertThat(out.toString()).contains("[warn] Skill が見つかりません: missing");
    assertThat(out.toString()).contains("実行スキル: sample");
  }

  @Test
  void returnsOriginalRequestWhenNoSkillIsSelected() {
    AgentSkillSelectionService selectionService = Mockito.mock(AgentSkillSelectionService.class);
    when(selectionService.select("hello")).thenReturn(new AgentSkillSelection(List.of(), List.of(), List.of(), "hello"));
    AgentSkillAdvisor advisor = new AgentSkillAdvisor(selectionService, new AgentSkillPromptRenderer());
    ChatClientRequest request = request("hello");

    ChatClientRequest advised = advisor.before(request, Mockito.mock(AdvisorChain.class));

    assertThat(advised).isSameAs(request);
  }

  private ChatClientRequest request(String text) {
    Prompt prompt = new Prompt(UserMessage.builder()
        .text(text)
        .build(),
        OpenAiChatOptions.builder()
            .model("test-model")
            .build());
    return new ChatClientRequest(prompt, Map.of());
  }

  private AgentSkill skill(String name) {
    return new AgentSkill(name, name + " description", true, Path.of(name), Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
