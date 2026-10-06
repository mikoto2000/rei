package dev.mikoto2000.rei.skills;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.prompt.Prompt;
import dev.mikoto2000.rei.core.stagnation.*;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

class SkillSelectionTokenLimitTest {
  @Test void usageBearingSkillResponseSharesRunLimitAndCannotFallbackOnExhaustion() {
    var model=mock(ChatModel.class);
    when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("[\"sample\"]"))),
        ChatResponseMetadata.builder().usage(new DefaultUsage(1,2)).build()));
    var skill=new AgentSkill("sample","description",true,Path.of("sample"),Path.of("sample/SKILL.md"),"instructions");
    var selector=new AgentSkillImplicitSelector(model,new InMemoryAgentSkillRepository(List.of(skill)));
    var budget=new OutputLimitRunBudget(0,10,null,2);var run=new RunExecutionContext("run",budget,null,null,null);
    assertThatThrownBy(()->selector.select("request",Set.of(),List.of(skill),run.modelCallBudget()))
        .isInstanceOf(ExecutionStoppedException.class).hasMessage("TOKEN_BUDGET_EXCEEDED");
    assertThat(budget.totalTokens()).isEqualTo(3);verify(model,never()).call(anyString());
  }
  @Test void missingSkillUsageStopsInsteadOfReturningEmptySelection() {
    var model=mock(ChatModel.class);when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("[]")))));
    var skill=new AgentSkill("sample","description",true,Path.of("sample"),Path.of("sample/SKILL.md"),"instructions");
    var selector=new AgentSkillImplicitSelector(model,new InMemoryAgentSkillRepository(List.of(skill)));
    var run=new RunExecutionContext("run",new OutputLimitRunBudget(0,10,null,10),null,null,null);
    assertThatThrownBy(()->selector.select("request",Set.of(),List.of(skill),run.modelCallBudget()))
        .hasMessage("TOKEN_USAGE_UNKNOWN");
  }
}
