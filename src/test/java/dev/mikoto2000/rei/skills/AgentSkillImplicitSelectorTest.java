package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.beans.factory.ObjectProvider;

class AgentSkillImplicitSelectorTest {

  @Test
  void selectsSkillsFromLlmJsonArray() {
    AgentSkill skill = skill("skill-a");
    AgentSkillImplicitSelector selector = selector("[\"skill-a\"]", skill);

    List<AgentSkill> selected = selector.select("please use it", Set.of());

    assertThat(selected).containsExactly(skill);
  }

  @Test
  void returnsEmptyWhenLlmReturnsEmptyArray() {
    AgentSkillImplicitSelector selector = selector("[]", skill("skill-a"));

    assertThat(selector.select("general chat", Set.of())).isEmpty();
  }

  @Test
  void ignoresUnknownSkillNames() {
    AgentSkillImplicitSelector selector = selector("[\"missing\"]", skill("skill-a"));

    assertThat(selector.select("please use it", Set.of())).isEmpty();
  }

  @Test
  void returnsEmptyWhenJsonParsingFails() {
    AgentSkillImplicitSelector selector = selector("not json", skill("skill-a"));

    assertThat(selector.select("please use it", Set.of())).isEmpty();
  }

  private AgentSkillImplicitSelector selector(String llmResponse, AgentSkill... skills) {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ObjectProvider<ChatClient> provider = Mockito.mock(ObjectProvider.class);
    when(provider.getObject()).thenReturn(chatClient);
    when(chatClient.prompt(anyString())).thenReturn(requestSpec);
    when(requestSpec.call().content()).thenReturn(llmResponse);
    return new AgentSkillImplicitSelector(provider, new InMemoryAgentSkillRepository(List.of(skills)));
  }

  private AgentSkill skill(String name) {
    return new AgentSkill(name, name + " description", true, Path.of(name), Path.of(name).resolve("SKILL.md"),
        name + " instructions");
  }
}
