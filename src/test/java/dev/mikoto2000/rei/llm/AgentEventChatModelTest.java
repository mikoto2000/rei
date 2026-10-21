package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.LlmRequestFailedPayload;
import dev.mikoto2000.rei.event.LlmRequestStartedPayload;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** エージェントイベント応答生成に使うモデルの結果と状態の契約を検証するテスト。 */
class AgentEventChatModelTest {

  /** 作業状況呼び出し回数について、応答一覧を返すことを確認する。 */
  @Test
  void activityCallsReturnResponsesAndPropagateErrorsWithoutNotifications() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("detect activity");
    ChatResponse response = mock(ChatResponse.class);
    var failure = new IllegalStateException("activity failed");
    when(delegate.call(prompt)).thenReturn(response).thenThrow(failure);
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);
    var model =
        new AgentEventChatModel(
            LlmFeature.ACTIVITY, delegate, new AgentEventFactory(Clock.systemUTC()), bus);

    assertEquals(response, model.call(prompt));
    assertEquals(failure, assertThrows(IllegalStateException.class, () -> model.call(prompt)));
    assertEquals(List.of(), events);
  }

  /** 作業状況逐次データ一覧について、トークン数を返すことを確認する。 */
  @Test
  void activityStreamsReturnTokensAndPropagateErrorsWithoutNotifications() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("detect activity");
    ChatResponse response = mock(ChatResponse.class);
    var failure = new IllegalStateException("activity failed");
    when(delegate.stream(prompt))
        .thenReturn(Flux.just(response, response))
        .thenReturn(Flux.error(failure));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);
    var model =
        new AgentEventChatModel(
            LlmFeature.ACTIVITY, delegate, new AgentEventFactory(Clock.systemUTC()), bus);

    assertEquals(List.of(response, response), model.stream(prompt).collectList().block());
    assertEquals(
        failure, assertThrows(IllegalStateException.class, () -> model.stream(prompt).blockLast()));
    assertEquals(List.of(), events);
  }

  /** 用途に応じたモデル利用で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void publishesFeatureLifecycleForSynchronousCalls() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("select a skill");
    ChatResponse response = mock(ChatResponse.class);
    when(delegate.call(prompt)).thenReturn(response);
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    ChatResponse actual =
        new AgentEventChatModel(
                LlmFeature.AGENT_SKILLS, delegate, new AgentEventFactory(Clock.systemUTC()), bus)
            .call(prompt);

    assertEquals(response, actual);
    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.get(0).type());
    assertEquals(
        LlmFeature.AGENT_SKILLS, ((LlmRequestStartedPayload) events.get(0).payload()).feature());
    assertEquals(AgentEventType.LLM_RESPONSE_COMPLETED, events.get(1).type());
  }

  /** 用途に応じたモデル利用で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void publishesCompletionOnlyForSuccessfulStreams() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("search");
    when(delegate.stream(prompt)).thenReturn(Flux.error(new IllegalStateException("boom")));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    new AgentEventChatModel(
            LlmFeature.SEARCH, delegate, new AgentEventFactory(Clock.systemUTC()), bus)
        .stream(prompt).onErrorComplete().blockLast();

    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.getFirst().type());
    assertFalse(
        events.stream().anyMatch(event -> event.type() == AgentEventType.LLM_RESPONSE_COMPLETED));
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void publishesFailureForSynchronousCallErrors() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("fail");
    when(delegate.call(prompt)).thenThrow(new IllegalStateException("boom"));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    assertThrows(
        IllegalStateException.class,
        () ->
            new AgentEventChatModel(
                    LlmFeature.CHAT, delegate, new AgentEventFactory(Clock.systemUTC()), bus)
                .call(prompt));

    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.get(0).type());
    assertEquals(AgentEventType.LLM_REQUEST_FAILED, events.get(1).type());
  }

  /** 用途に応じたモデル利用で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void publishesFirstTokenForStreams() {
    ChatModel delegate = mock(ChatModel.class);
    Prompt prompt = new Prompt("stream");
    ChatResponse response = mock(ChatResponse.class);
    when(delegate.stream(prompt)).thenReturn(Flux.just(response, response));
    List<AgentEvent> events = new ArrayList<>();
    InMemoryAgentEventBus bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);

    new AgentEventChatModel(
            LlmFeature.CHAT, delegate, new AgentEventFactory(Clock.systemUTC()), bus)
        .stream(prompt).blockLast();

    assertEquals(AgentEventType.LLM_REQUEST_STARTED, events.get(0).type());
    assertEquals(AgentEventType.LLM_RESPONSE_FIRST_TOKEN, events.get(1).type());
    assertEquals(AgentEventType.LLM_RESPONSE_COMPLETED, events.get(2).type());
    assertEquals(3, events.size());
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void summaryPurposeLabelsCallAndStreamWithoutChangingTheDelegatePrompt() {
    for (boolean streaming : List.of(false, true)) {
      var delegate = mock(ChatModel.class);
      var prompt =
          new Prompt(
              "summary",
              org.springframework.ai.openai.OpenAiChatOptions.builder()
                  .model("configured-model")
                  .toolContext(java.util.Map.of("rei.modelCallPurpose", "context-summary"))
                  .build());
      var response = mock(ChatResponse.class);
      when(delegate.call(prompt)).thenReturn(response);
      when(delegate.stream(prompt)).thenReturn(Flux.just(response));
      var events = new ArrayList<AgentEvent>();
      var bus = new InMemoryAgentEventBus();
      bus.subscribe(events::add);
      var model =
          new AgentEventChatModel(
              LlmFeature.CHAT, delegate, new AgentEventFactory(Clock.systemUTC()), bus);
      assertEquals(response, streaming ? model.stream(prompt).blockLast() : model.call(prompt));
      assertEquals(
          "context-summary", ((LlmRequestStartedPayload) events.getFirst().payload()).feature());
      assertEquals("configured-model", prompt.getOptions().getModel());
      if (streaming) org.mockito.Mockito.verify(delegate).stream(prompt);
      else org.mockito.Mockito.verify(delegate).call(prompt);
    }
  }

  /** 用途に応じたモデル利用で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unknownPurposeMarkersStayPrivateAndDoNotChangeFeature() {
    for (Object marker : List.of("private-purpose-secret", 27)) {
      for (boolean streaming : List.of(false, true)) {
        var delegate = mock(ChatModel.class);
        var prompt =
            new Prompt(
                "chat",
                org.springframework.ai.openai.OpenAiChatOptions.builder()
                    .toolContext(java.util.Map.of("rei.modelCallPurpose", marker))
                    .build());
        var response = mock(ChatResponse.class);
        when(delegate.call(prompt)).thenReturn(response);
        when(delegate.stream(prompt)).thenReturn(Flux.just(response));
        var events = new ArrayList<AgentEvent>();
        var bus = new InMemoryAgentEventBus();
        bus.subscribe(events::add);
        var model =
            new AgentEventChatModel(
                LlmFeature.CHAT, delegate, new AgentEventFactory(Clock.systemUTC()), bus);
        if (streaming) model.stream(prompt).blockLast();
        else model.call(prompt);
        assertEquals(
            LlmFeature.CHAT, ((LlmRequestStartedPayload) events.getFirst().payload()).feature());
        assertThat(events.toString()).doesNotContain("private-purpose-secret");
      }
    }
  }

  /** 機能を維持することを確認する。 */
  @Test
  void nonChatFeaturesKeepTheirFeatureWhenSummaryMarkerIsPresent() {
    var delegate = mock(ChatModel.class);
    var prompt =
        new Prompt(
            "skill",
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .toolContext(java.util.Map.of("rei.modelCallPurpose", "context-summary"))
                .build());
    when(delegate.call(prompt)).thenReturn(mock(ChatResponse.class));
    var events = new ArrayList<AgentEvent>();
    var bus = new InMemoryAgentEventBus();
    bus.subscribe(events::add);
    new AgentEventChatModel(
            LlmFeature.AGENT_SKILLS, delegate, new AgentEventFactory(Clock.systemUTC()), bus)
        .call(prompt);
    assertEquals(
        LlmFeature.AGENT_SKILLS,
        ((LlmRequestStartedPayload) events.getFirst().payload()).feature());
  }

  /** 用途に応じたモデル利用で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void providerFailureEventsHaveSpecificSafeCodesForCallAndStream() {
    final String secret = "private-provider-message-and-body";
    for (boolean streaming : List.of(false, true)) {
      var delegate = mock(ChatModel.class);
      var prompt = new Prompt("fail");
      var failure =
          org.springframework.web.reactive.function.client.WebClientResponseException.create(
              401,
              secret,
              org.springframework.http.HttpHeaders.EMPTY,
              secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
              java.nio.charset.StandardCharsets.UTF_8);
      when(delegate.call(prompt)).thenThrow(failure);
      when(delegate.stream(prompt)).thenReturn(Flux.error(failure));
      var events = new ArrayList<AgentEvent>();
      var bus = new InMemoryAgentEventBus();
      bus.subscribe(events::add);
      var model =
          new AgentEventChatModel(
              LlmFeature.CHAT, delegate, new AgentEventFactory(Clock.systemUTC()), bus);
      assertThrows(
          org.springframework.web.reactive.function.client.WebClientResponseException.class,
          () -> {
            if (streaming) model.stream(prompt).blockLast();
            else model.call(prompt);
          });
      var event =
          events.stream()
              .filter(value -> value.type() == AgentEventType.LLM_REQUEST_FAILED)
              .findFirst()
              .orElseThrow();
      var error = ((LlmRequestFailedPayload) event.payload()).error();
      assertEquals("authentication_failed", error.code());
      assertThat(error.message()).contains("HTTP 401");
      assertThat(events.toString()).doesNotContain(secret);
    }
  }
}
