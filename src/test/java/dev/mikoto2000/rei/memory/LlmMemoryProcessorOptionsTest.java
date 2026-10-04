package dev.mikoto2000.rei.memory;

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
import dev.mikoto2000.rei.llm.*;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.memory.service.LlmMemoryProcessor;
import reactor.core.publisher.Flux;

class LlmMemoryProcessorOptionsTest {
  @Test void requestRetainsConfiguredOptionsAndDisablesToolsWithoutMutatingTheSource() {
    var provider=mock(LlmModelProvider.class);var model=mock(ChatModel.class);
    var callback=FunctionToolCallback.builder("unexpected",(String input)->input)
        .description("must not be exposed").inputType(String.class).build();
    var configured=OpenAiChatOptions.builder().model("memory-model").temperature(0.2).maxTokens(456)
        .extraBody(Map.of("custom_parameter",7)).toolCallbacks(List.of(callback)).toolChoice("required").build();
    when(provider.memoryChatModel()).thenReturn(model);
    when(provider.chatOptions(LlmFeature.MEMORY,null)).thenReturn(configured);
    when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(
        new Generation(new AssistantMessage("{\"memories\":[]}"))))));

    assertTrue(processor(provider).extract(List.of()).isEmpty());

    var prompt=ArgumentCaptor.forClass(Prompt.class);verify(model).stream(prompt.capture());
    var actual=(OpenAiChatOptions)prompt.getValue().getOptions();
    assertNotSame(configured,actual);assertEquals("memory-model",actual.getModel());
    assertEquals(0.2,actual.getTemperature());assertEquals(456,actual.getMaxTokens());
    assertEquals(Map.of("custom_parameter",7),actual.getExtraBody());
    assertTrue(actual.getToolCallbacks().isEmpty());assertEquals("none",actual.getToolChoice());
    assertEquals(List.of(callback),configured.getToolCallbacks());assertEquals("required",configured.getToolChoice());
  }

  @Test void rawToolDefinitionsAreRejectedBeforeSendingThePrompt() {
    var provider=mock(LlmModelProvider.class);var model=mock(ChatModel.class);
    when(provider.memoryChatModel()).thenReturn(model);
    when(provider.chatOptions(LlmFeature.MEMORY,null)).thenReturn(OpenAiChatOptions.builder()
        .extraBody(Map.of("tools",List.of(Map.of("type","function")))).build());

    assertThrows(IllegalArgumentException.class,()->processor(provider).extract(List.of()));

    verify(model,never()).stream(any(Prompt.class));
  }

  private LlmMemoryProcessor processor(LlmModelProvider provider) {
    return new LlmMemoryProcessor(provider,new MemoryProperties(true,20,80,10,3,2000,60,null));
  }
}
