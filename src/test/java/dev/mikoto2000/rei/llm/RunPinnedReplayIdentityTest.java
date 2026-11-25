package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.responses.OpenAiResponsesChatOptions;

class RunPinnedReplayIdentityTest {
  @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;

  @Test
  void nativePayloadBindingUsesTheAcceptedIdentityWithoutResamplingAFeature() {
    var store = org.mockito.Mockito.mock(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class);
    var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "session", directory, "project");
    var exchange = new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Exchange(store,
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Scope("project", directory.toString(), "session"),
        "run", "exchange");
    org.mockito.Mockito.when(store.newExchange(owner)).thenReturn(exchange);
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("payloads", store);
    var models = new LlmModelProvider(org.mockito.Mockito.mock(org.springframework.ai.chat.model.ChatModel.class),
        new LlmProperties());
    models.configureModelMessagePayloads(beans.getBeanProvider(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class));
    var pin = pin("registration-first", Map.of());
    var bound = models.bindRunOptions(RunNativeOptions.options(pin), owner);
    assertThat(bound.getToolContext()).containsEntry(
        dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin(exchange.ownerKey(), pin.fingerprint()));
  }

  @Test
  void boundResponsesUseTheWholeAcceptedIdentity() {
    var pin = pin("registration-first", Map.of());
    var bound = ResponsesReasoningReplay.bind(response(), prompt(pin), pin.endpoint());
    assertThat(bound.getResult().getOutput().getMetadata())
        .containsEntry(ResponsesReasoningReplay.ORIGIN_ENDPOINT, pin.fingerprint());
  }

  @Test
  void aSameRunResponseWithTheAcceptedIdentityCanBeReplayed() {
    var pin = pin("registration-first", Map.of());
    var assistant = AssistantMessage.builder().content("fixture").properties(Map.of(
        ResponsesReasoningReplay.ORIGIN_OWNER, "owner",
        ResponsesReasoningReplay.ORIGIN_ENDPOINT, pin.fingerprint())).build();
    assertThatCode(() -> ResponsesReasoningReplay.prepare(
        new Prompt(List.of(assistant), prompt(pin).getOptions()), pin.endpoint())).doesNotThrowAnyException();
  }

  @ParameterizedTest
  @ValueSource(strings = {"registration", "settings"})
  void replayCannotCrossAChangedAcceptedBindingEvenAtTheSameUrl(String change) {
    var first = pin("registration-first", Map.of());
    var second = change.equals("registration")
        ? pin("registration-second", Map.of()) : pin("registration-first", Map.of("temperature", 0.2));
    var bound = ResponsesReasoningReplay.bind(response(), prompt(first), first.endpoint());
    assertThatThrownBy(() -> ResponsesReasoningReplay.prepare(
        new Prompt(List.of(bound.getResult().getOutput()), prompt(second).getOptions()), second.endpoint()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("endpoint");
  }

  @Test
  void payloadOriginUsesTheSameAcceptedIdentityAsTheResponse() {
    var pin = pin("registration-first", Map.of());
    var options = (OpenAiResponsesChatOptions) prompt(pin).getOptions();
    var context = new java.util.HashMap<>(options.getToolContext());
    context.put(dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
        new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.Origin("owner", pin.fingerprint()));
    assertThatCode(() -> ResponsesReasoningReplay.prepare(
        new Prompt(List.of(new UserMessage("fixture")), options.mutate().toolContext(null).toolContext(context).build()),
        pin.endpoint())).doesNotThrowAnyException();
  }

  @Test
  void malformedAcceptedContextIsRejectedEvenWithoutAssistantHistory() {
    var pin = pin("registration-first", Map.of());
    var options = OpenAiResponsesChatOptions.builder().model("model").toolContext(Map.of(
        ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "owner",
        dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, "untrusted")).build();
    assertThatThrownBy(() -> ResponsesReasoningReplay.prepare(new Prompt("fixture", options), pin.endpoint()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void callersWithoutAnAcceptedSnapshotRetainTheirExistingIdentity() {
    var endpoint = pin("registration-first", Map.of()).endpoint();
    var request = new Prompt("fixture", OpenAiResponsesChatOptions.builder().model("model")
        .toolContext(Map.of(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "owner")).build());
    var bound = ResponsesReasoningReplay.bind(response(), request, endpoint);
    assertThat(bound.getResult().getOutput().getMetadata())
        .containsEntry(ResponsesReasoningReplay.ORIGIN_ENDPOINT, endpoint.fingerprint());
    assertThatCode(() -> ResponsesReasoningReplay.prepare(
        new Prompt(List.of(bound.getResult().getOutput()), request.getOptions()), endpoint)).doesNotThrowAnyException();
  }

  private Prompt prompt(RunLlmConnection pin) {
    var options = (OpenAiResponsesChatOptions) RunNativeOptions.options(pin);
    var context = new java.util.HashMap<>(options.getToolContext());
    context.put(ResponsesReasoningReplay.OWNER_CONTEXT_KEY, "owner");
    return new Prompt(List.of(new UserMessage("fixture")), options.mutate().toolContext(null).toolContext(context).build());
  }

  private ChatResponse response() {
    return new ChatResponse(List.of(new Generation(new AssistantMessage("fixture"))));
  }

  private RunLlmConnection pin(String registration, Map<String, Object> generation) {
    return new RunLlmConnection("plan", true, LlmProvider.CHATGPT_PLAN, LlmAuthentication.CHATGPT_OAUTH,
        "account", registration, new ResolvedLlmEndpoint("plan", 1, LlmApiType.RESPONSES,
            "https://api.openai.com/v1", "plan", "model",
            new LlmCapabilities(ReasoningReplayMode.ENCRYPTED, 36, Set.of()), null, null, Duration.ofSeconds(5)),
        Map.of(), generation);
  }
}
