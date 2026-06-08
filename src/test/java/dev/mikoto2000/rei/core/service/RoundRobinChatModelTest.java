package dev.mikoto2000.rei.core.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

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
}
