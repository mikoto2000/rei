package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;

import reactor.core.publisher.Flux;

class FallbackChatModelTest {

  @Test
  void callFallsBackToDefaultModelWhenPrimaryFails() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", ChatOptions.builder().model("feature-model").temperature(0.2).build());
    ChatResponse fallbackResponse = response("fallback");

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(fallbackResponse);

    FallbackChatModel model = new FallbackChatModel("search", primary, fallback, "feature-model");

    assertThat(model.call(prompt)).isSameAs(fallbackResponse);
    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isNull();
    assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.2);
  }

  @Test
  void streamFallsBackToDefaultModelWhenPrimaryFails() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", ChatOptions.builder().model("feature-model").build());
    ChatResponse fallbackResponse = response("fallback");

    when(primary.stream(prompt)).thenReturn(Flux.error(new RuntimeException("connection failed")));
    when(fallback.stream(Mockito.any(Prompt.class))).thenReturn(Flux.just(fallbackResponse));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");

    assertThat(model.stream(prompt).collectList().block()).containsExactly(fallbackResponse);
  }

  @Test
  void callKeepsPromptModelWhenItIsNotPrimaryFeatureModel() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", ChatOptions.builder().model("current-default-model").build());

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(response("fallback"));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");
    model.call(prompt);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isEqualTo("current-default-model");
  }

  @Test
  void callFallsBackWithoutCallingMutateWhenOptionsDoNotSupportMutate() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", new MinimalChatOptions("feature-model", 0.4));

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(response("fallback"));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");
    model.call(prompt);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isNull();
    assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.4);
  }

  @Test
  void callRemovesPrimaryModelFromOpenAiOptionsWhenFallingBack() {
    ChatModel primary = Mockito.mock(ChatModel.class);
    ChatModel fallback = Mockito.mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", OpenAiChatOptions.builder()
        .model("feature-model")
        .temperature(0.5)
        .build());

    when(primary.call(prompt)).thenThrow(new RuntimeException("connection failed"));
    when(fallback.call(Mockito.any(Prompt.class))).thenReturn(response("fallback"));

    FallbackChatModel model = new FallbackChatModel("chat", primary, fallback, "feature-model");
    model.call(prompt);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(fallback).call(promptCaptor.capture());
    assertThat(promptCaptor.getValue().getOptions().getModel()).isNull();
    assertThat(promptCaptor.getValue().getOptions().getTemperature()).isEqualTo(0.5);
  }

  private static ChatResponse response(String text) {
    return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
  }

  private record MinimalChatOptions(String model, Double temperature) implements ChatOptions {
    @Override
    public String getModel() {
      return model;
    }

    @Override
    public Double getTemperature() {
      return temperature;
    }

    @Override
    public Double getFrequencyPenalty() {
      return null;
    }

    @Override
    public Integer getMaxTokens() {
      return null;
    }

    @Override
    public Double getPresencePenalty() {
      return null;
    }

    @Override
    public List<String> getStopSequences() {
      return null;
    }

    @Override
    public Integer getTopK() {
      return null;
    }

    @Override
    public Double getTopP() {
      return null;
    }

    @Override
    public ChatOptions copy() {
      return this;
    }
  }
}
