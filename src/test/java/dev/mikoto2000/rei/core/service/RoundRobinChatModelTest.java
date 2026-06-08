package dev.mikoto2000.rei.core.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.mockito.ArgumentCaptor;

class RoundRobinChatModelTest {

  @Test
  void callDelegatesInRoundRobinOrder() {
    ChatModel first = mock(ChatModel.class);
    ChatModel second = mock(ChatModel.class);
    Prompt prompt = new Prompt("hello");
    RoundRobinChatModel model = new RoundRobinChatModel(List.of(first, second));

    model.call(prompt);
    model.call(prompt);
    model.call(prompt);

    verify(first, org.mockito.Mockito.times(2)).call(prompt);
    verify(second).call(prompt);
  }

  @Test
  void streamDelegatesInRoundRobinOrder() {
    ChatModel first = mock(ChatModel.class);
    ChatModel second = mock(ChatModel.class);
    Prompt prompt = new Prompt("hello");
    when(first.stream(prompt)).thenReturn(reactor.core.publisher.Flux.empty());
    when(second.stream(prompt)).thenReturn(reactor.core.publisher.Flux.empty());
    RoundRobinChatModel model = new RoundRobinChatModel(List.of(first, second));

    model.stream(prompt);
    model.stream(prompt);

    verify(first).stream(prompt);
    verify(second).stream(prompt);
  }

  @Test
  void callOverridesPromptModelWithSelectedDelegateModel() {
    ChatModel first = mock(ChatModel.class);
    ChatModel second = mock(ChatModel.class);
    Prompt prompt = new Prompt("hello", OpenAiChatOptions.builder().model("global-model").build());
    RoundRobinChatModel model = new RoundRobinChatModel(List.of(
        new RoundRobinChatModel.Delegate(first, "server-a-model"),
        new RoundRobinChatModel.Delegate(second, "server-b-model")),
        true);

    model.call(prompt);
    model.call(prompt);

    ArgumentCaptor<Prompt> firstPrompt = ArgumentCaptor.forClass(Prompt.class);
    ArgumentCaptor<Prompt> secondPrompt = ArgumentCaptor.forClass(Prompt.class);
    verify(first).call(firstPrompt.capture());
    verify(second).call(secondPrompt.capture());
    org.junit.jupiter.api.Assertions.assertEquals("server-a-model", firstPrompt.getValue().getOptions().getModel());
    org.junit.jupiter.api.Assertions.assertEquals("server-b-model", secondPrompt.getValue().getOptions().getModel());
  }
}
