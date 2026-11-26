package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;

/** Shared Run input ownership for named APIs; no client construction, HTTP, or credential. */
class NamedInferenceInputBindingTest {
  @TempDir Path directory;

  @ParameterizedTest
  @EnumSource(LlmApiType.class)
  void namedApisBindTheSameAcceptedIdentityToProtectedInput(LlmApiType api) {
    var store = mock(ModelMessagePayloadStore.class);
    var defaults = mock(ChatModel.class);
    var owner = new AgentRunContext("run", "session", directory, "project");
    var exchange =
        new ModelMessagePayloadStore.Exchange(
            store, ModelMessagePayloadStore.Scope.forOwner(owner), owner.runId(), "fixture");
    when(store.newExchange(owner)).thenReturn(exchange);
    var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
    beans.addBean("payloads", store);
    var models = new LlmModelProvider(defaults, new LlmProperties());
    models.configureModelMessagePayloads(beans.getBeanProvider(ModelMessagePayloadStore.class));
    var pin = pin(api, true);
    var bound = models.bindRunOptions(RunNativeOptions.options(pin), owner);
    assertThat(bound.getToolContext())
        .containsEntry(ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY, exchange)
        .containsEntry(
            ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY,
            new ModelMessagePayloadStore.Origin(exchange.ownerKey(), pin.fingerprint()));
    verify(store).newExchange(owner);
    verifyNoInteractions(defaults);
  }

  @Test
  void legacyChatCompletionsRetainsItsExistingInputPath() {
    var defaults = mock(ChatModel.class);
    var models = new LlmModelProvider(defaults, new LlmProperties());
    var original = RunNativeOptions.options(pin(LlmApiType.CHAT_COMPLETIONS, false));
    var bound =
        models.bindRunOptions(
            original, new AgentRunContext("run", "session", directory, "project"));
    assertThat(bound).isSameAs(original);
    assertThat(bound.getToolContext())
        .doesNotContainKeys(
            ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
            ModelMessagePayloadStore.ORIGIN_CONTEXT_KEY);
    verifyNoInteractions(defaults);
  }

  private static RunLlmConnection pin(LlmApiType api, boolean named) {
    var endpoint =
        new ResolvedLlmEndpoint(
            "local",
            1,
            api,
            "http://fixture.invalid/v1",
            "chat-endpoint:local",
            "fixture-model",
            new LlmCapabilities(ReasoningReplayMode.NONE, 8, Set.of()),
            512,
            null,
            Duration.ofSeconds(5));
    return new RunLlmConnection(
        "local",
        named,
        LlmProvider.OPENAI_COMPATIBLE,
        LlmAuthentication.NONE,
        null,
        null,
        endpoint,
        Map.of(
            LlmEndpointCapabilities.Capability.MAX_OUTPUT_TOKENS,
            LlmEndpointCapabilities.Support.SUPPORTED),
        Map.of(api == LlmApiType.RESPONSES ? "max_output_tokens" : "max_tokens", 512));
  }
}
