package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.function.FunctionToolCallback;
import dev.mikoto2000.rei.llm.LlmModelProvider;
import reactor.core.publisher.Flux;

class SpringPaperLanguageModelTest {
  @Test void modelNameComesFromConfiguredModelOptions() {
    var provider=mock(LlmModelProvider.class);var model=mock(ChatModel.class);
    when(provider.subAgentChatModel()).thenReturn(model);
    when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().model("configured-model").build());
    when(provider.model("chat","configured-model")).thenReturn("feature-model");

    assertEquals("feature-model",new SpringPaperLanguageModel(provider,new PaperProperties()).model());
  }

  @Test void generatePreservesConfiguredOptionsWithoutExposingCallbacksOrChangingTheSource() {
    var provider=mock(LlmModelProvider.class);var model=mock(ChatModel.class);
    var callback=FunctionToolCallback.builder("unexpected",(String input)->input)
        .description("must not be exposed").inputType(String.class).build();
    var configured=OpenAiChatOptions.builder().model("paper-model").temperature(0.3).maxTokens(789)
        .extraBody(Map.of("custom_parameter",7)).toolCallbacks(List.of(callback)).toolChoice("required").build();
    when(provider.subAgentChatModel()).thenReturn(model);
    when(provider.model(eq("chat"),nullable(String.class))).thenReturn("paper-model");
    when(provider.chatOptions("chat","paper-model")).thenReturn(configured);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(
        new Generation(new AssistantMessage("result"))))));

    assertEquals("result",new SpringPaperLanguageModel(provider,new PaperProperties())
        .generate("system","source",PaperOperation.local("s")));

    var prompt=ArgumentCaptor.forClass(Prompt.class);verify(model).stream(prompt.capture());
    var actual=(OpenAiChatOptions)prompt.getValue().getOptions();
    assertNotSame(configured,actual);assertEquals("paper-model",actual.getModel());
    assertEquals(0.3,actual.getTemperature());assertEquals(789,actual.getMaxTokens());
    assertEquals(Map.of("custom_parameter",7),actual.getExtraBody());
    assertTrue(actual.getToolCallbacks().isEmpty());assertEquals("none",actual.getToolChoice());
    assertEquals(List.of(callback),configured.getToolCallbacks());assertEquals("required",configured.getToolChoice());
  }

  @Test void rawToolDefinitionsAreRejectedBeforeSendingThePrompt() {
    var provider=mock(LlmModelProvider.class);var model=mock(ChatModel.class);
    when(provider.subAgentChatModel()).thenReturn(model);
    when(provider.model(eq("chat"),nullable(String.class))).thenReturn("paper-model");
    when(provider.chatOptions("chat","paper-model")).thenReturn(OpenAiChatOptions.builder()
        .extraBody(Map.of("tools",List.of(Map.of("type","function")))).build());

    assertThrows(IllegalArgumentException.class,()->new SpringPaperLanguageModel(provider,new PaperProperties())
        .generate("system","source",PaperOperation.local("s")));

    verify(model,never()).stream(any(Prompt.class));
  }
}
