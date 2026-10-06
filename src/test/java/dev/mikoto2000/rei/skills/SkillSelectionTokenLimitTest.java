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
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void tokenLimitedSelectionUsesProviderOptionsAndDisablesToolsOnTheWire(boolean completionTokens) {
    var requests = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
    var defaults = org.springframework.ai.openai.OpenAiChatOptions.builder()
        .baseUrl("https://skill-selection.invalid/v1").apiKey("test").model("default-model").maxRetries(0);
    if (completionTokens) defaults.maxCompletionTokens(4096);
    else defaults.maxTokens(4096);
    var model = org.springframework.ai.openai.OpenAiChatModel.builder().options(defaults.build())
        .httpClientBuilderCustomizer(builder -> builder.interceptor(chain -> {
          var body = new okio.Buffer();
          chain.request().body().writeTo(body);
          requests.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(body.readByteArray()));
          return new okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
              .code(200).message("OK").body(okhttp3.ResponseBody.create("""
                  {"id":"skill-selection","object":"chat.completion","created":0,"model":"skill-model",
                   "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"[\\\"sample\\\"]"}}],
                   "usage":{"prompt_tokens":2,"completion_tokens":1,"total_tokens":3}}
                  """, okhttp3.MediaType.get("application/json"))).build();
        })).build();
    var properties = new dev.mikoto2000.rei.llm.LlmProperties();
    var feature = new dev.mikoto2000.rei.llm.LlmProperties.Server();
    feature.setModel("skill-model");
    feature.setMaxOutputTokens(128);
    feature.setTemperature(0.25);
    properties.getFeatures().put(dev.mikoto2000.rei.llm.LlmFeature.AGENT_SKILLS, feature);
    var provider = new dev.mikoto2000.rei.llm.LlmModelProvider(model, properties);
    var skill = new AgentSkill("sample", "description", true, Path.of("sample"), Path.of("sample/SKILL.md"), "instructions");
    var selector = new AgentSkillImplicitSelector(provider, new InMemoryAgentSkillRepository(List.of(skill)));
    var budget = new OutputLimitRunBudget(0, 10, null, 10);
    var run = new RunExecutionContext("run", budget, null, null, null);

    assertThat(selector.select("request", Set.of(), List.of(skill), run.modelCallBudget())).containsExactly(skill);

    assertThat(requests).hasSize(1);
    var request = requests.getFirst();
    assertThat(request.path("model").asText()).isEqualTo("skill-model");
    assertThat(request.path(completionTokens ? "max_completion_tokens" : "max_tokens").asInt()).isEqualTo(128);
    assertThat(request.has(completionTokens ? "max_tokens" : "max_completion_tokens")).isFalse();
    assertThat(request.path("temperature").asDouble()).isEqualTo(0.25);
    assertThat(request.path("tool_choice").asText()).isEqualTo("none");
    assertThat(request.has("tools")).isFalse();
    assertThat(budget.totalTokens()).isEqualTo(3);
    assertThat(budget.usageUnknown()).isFalse();
    assertThat(budget.remainingLlmCalls()).isEqualTo(9);
    assertThat(model.getOptions().getModel()).isEqualTo("default-model");
  }

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
