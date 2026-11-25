package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.configuration.CoreProperties;
import dev.mikoto2000.rei.core.configuration.SystemPromptService;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;

class RunChatClientCompositionTest {
  static Stream<Arguments> modesAndApis() {
    return Stream.of(AgentRunContext.Mode.values()).flatMap(mode ->
        Stream.of(LlmApiType.values()).map(api -> Arguments.of(mode, api)));
  }

  @ParameterizedTest
  @MethodSource("modesAndApis")
  void allChatModesUseTheProvidedRunModelAndCleanProtocolDefaults(AgentRunContext.Mode mode, LlmApiType api) {
    var prompts = new ArrayList<Prompt>();
    ChatModel routed = prompt -> {
      prompts.add(prompt);
      return new ChatResponse(List.of(new Generation(new AssistantMessage("pinned-answer"))));
    };
    var models = mock(LlmModelProvider.class);
    when(models.chatModel(LlmFeature.CHAT)).thenThrow(new AssertionError("Static main model was resolved"));
    when(models.subAgentChatModel()).thenThrow(new AssertionError("Chat used a helper model"));
    when(models.requestOptions(LlmFeature.CHAT, null, false))
        .thenThrow(new AssertionError("Chat inherited static connection defaults"));
    var system = mock(SystemPromptService.class);
    when(system.systemPrompt()).thenReturn("system-fixture");
    var provider = new LlmChatClientProvider(models, new CoreProperties("system-fixture", 100),
        system, dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build(),
        optional(), optional(), optional(), optional(), optional(), optional(), optional(), optional(),
        optional(), optional(), optional(), optional(), optional(), optional(), optional(), optional(),
        optional(), optional(), null, null);
    var client = provider.runChatClient(mode, api, routed);
    assertThat(client.prompt().user("fixture").call().content()).isEqualTo("pinned-answer");
    assertThat(prompts).hasSize(1);
    var options = (ToolCallingChatOptions) prompts.getFirst().getOptions();
    assertThat(api == LlmApiType.RESPONSES
        ? options instanceof org.springframework.ai.openai.responses.OpenAiResponsesChatOptions
        : options instanceof org.springframework.ai.openai.OpenAiChatOptions).isTrue();
    assertThat(options.getModel()).isEmpty();
    assertThat(options.getTemperature()).isNull();
    assertThat(options.getMaxTokens()).isNull();
    if (mode == AgentRunContext.Mode.CONVERSATION) assertThat(options.getToolCallbacks()).isEmpty();
    var endpoint = new ResolvedLlmEndpoint("fixture", 1, api, "http://fixture.invalid/v1", "fixture",
        "accepted-model", new LlmCapabilities(ReasoningReplayMode.NONE, 36, java.util.Set.of()),
        200, 0.2, java.time.Duration.ofSeconds(5));
    var pin = new RunLlmConnection("fixture", true, LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE, null, null, endpoint, java.util.Map.of(),
        java.util.Map.of("temperature", 0.2,
            api == LlmApiType.RESPONSES ? "max_output_tokens" : "max_tokens", 200));
    assertThat(client.prompt().user("fixture").options(RunNativeOptions.options(pin).mutate()).call().content())
        .isEqualTo("pinned-answer");
    var accepted = (ToolCallingChatOptions) prompts.getLast().getOptions();
    assertThat(accepted.getModel()).isEqualTo("accepted-model");
    assertThat(accepted.getTemperature()).isEqualTo(0.2);
    assertThat(accepted.getMaxTokens()).isEqualTo(200);
    assertThat(accepted.getToolContext()).containsEntry(
        dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, pin);
  }

  private <T> ObjectProvider<T> optional() {
    return mock();
  }
}
