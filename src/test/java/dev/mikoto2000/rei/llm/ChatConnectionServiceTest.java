package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatConnectionServiceTest {
  @TempDir Path root;

  @Test
  void independentStaticLocalInferencePreservesItsConfiguredModelAndNoAuth() throws Exception {
    try (var fixture = new PinnedChatModelsTest.Fixture()) {
      var properties = named("http://first.invalid/v1", "http://second.invalid/v1");
      var chat = properties.feature(LlmFeature.CHAT);
      chat.setBaseUrl(
          fixture
              .pin("static", "dedicated-background", LlmAuthentication.NONE)
              .endpoint()
              .baseUrl());
      chat.setModel("dedicated-background");
      properties.setFeature(LlmFeature.CHAT, chat);
      var accounts = mock(dev.mikoto2000.rei.modeloptions.ChatPlanAccounts.class);
      try (var service =
          service(properties, new ModelHolderService("static-holder"), new ArrayList<>(), true)) {
        service.setPlanAccounts(
            provider(dev.mikoto2000.rei.modeloptions.ChatPlanAccounts.class, accounts));
        service.selectModel("normal-chat-choice");
        service.select("second");
        var owner = owner("owned-independent", AgentRunContext.Mode.EXCLUSIVE);
        var pin = service.acceptIndependent(owner);
        assertThat(pin.endpoint().model()).isEqualTo("dedicated-background");
        assertThat(pin.authentication()).isEqualTo(LlmAuthentication.NONE);
        assertThat(
                service
                    .model()
                    .call(new Prompt("fixture", service.options(owner)))
                    .getResult()
                    .getOutput()
                    .getText())
            .isEqualTo("fixture-answer");
        assertThat(fixture.headers).containsExactly("<none>");
        assertThat(fixture.bodies.getFirst().path("model").asText())
            .isEqualTo("dedicated-background");
        org.mockito.Mockito.verifyNoInteractions(accounts);
      }
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"unchanged", "base-url", "api-key", "generation"})
  void restoredIndependentConnectionsRetainTheirBindingInsteadOfTheCurrentSelection(String change) {
    var properties = named("http://first.invalid/v1", "http://second.invalid/v1");
    var chat = properties.feature(LlmFeature.CHAT);
    chat.setBaseUrl("http://static-background.invalid/v1");
    chat.setModel("static-background");
    properties.setFeature(LlmFeature.CHAT, chat);
    RunLlmConnection saved;
    try (var service =
        service(properties, new ModelHolderService("holder"), new ArrayList<>(), true)) {
      saved = service.acceptIndependent(owner("old-independent", AgentRunContext.Mode.EXCLUSIVE));
    }
    if (!change.equals("unchanged")) {
      chat = properties.feature(LlmFeature.CHAT);
      if (change.equals("base-url")) chat.setBaseUrl("http://changed-background.invalid/v1");
      if (change.equals("api-key")) chat.setApiKey("PRIVATE_CHANGED_KEY");
      if (change.equals("generation")) chat.setEndpointGeneration(2);
      properties.setFeature(LlmFeature.CHAT, chat);
    }
    try (var service =
        service(properties, new ModelHolderService("new-holder"), new ArrayList<>(), true)) {
      service.select("second");
      var owner = owner("resumed-independent", AgentRunContext.Mode.EXCLUSIVE);
      if (!change.equals("unchanged")) {
        assertThatThrownBy(() -> service.restore(owner, saved))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("static connection binding")
            .hasMessageNotContaining("PRIVATE_CHANGED_KEY");
        assertThatThrownBy(() -> service.require(owner)).isInstanceOf(IllegalStateException.class);
      } else {
        service.restore(owner, saved);
        assertThat(service.acceptIndependent(owner)).isEqualTo(saved);
        assertThat(service.selection().endpoint()).isEqualTo("second");
      }
    }
  }

  @Test
  void anIndependentEntryCannotRelabelAnAlreadyAcceptedNamedRun() {
    var properties = named("http://first.invalid/v1", "http://second.invalid/v1");
    try (var service = service(properties, new ModelHolderService("holder"), new ArrayList<>())) {
      var owner = owner("already-normal", AgentRunContext.Mode.EXCLUSIVE);
      var pin = service.accept(owner);
      assertThatThrownBy(() -> service.acceptIndependent(owner))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("normal chat Run");
      assertThat(service.require(owner)).isSameAs(pin);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void independentInferenceCannotInheritASelectedLocalOrSubscriptionProfile(boolean plan)
      throws Exception {
    var properties = named("http://first.invalid/v1", "http://second.invalid/v1");
    if (plan) {
      var chat = properties.feature(LlmFeature.CHAT);
      var endpoints = new java.util.HashMap<>(chat.getEndpoints());
      var subscription = new LlmProperties.Server();
      subscription.setProvider("chatgpt-plan");
      subscription.setApi(LlmApiType.RESPONSES);
      subscription.setAccountRef("personal");
      subscription.setModel("plan-model");
      subscription.setServerCapabilities(
          Map.of(
              "responses", "supported", "streaming", "supported", "reasoning-replay", "supported"));
      subscription.setModelCapabilities(Map.of("reasoning-replay", "supported"));
      endpoints.put("second", subscription);
      chat.setEndpoints(endpoints);
      properties.setFeature(LlmFeature.CHAT, chat);
    }
    var accounts = mock(dev.mikoto2000.rei.modeloptions.ChatPlanAccounts.class);
    when(accounts.registration("personal")).thenReturn("fixture-registration");
    try (var service =
        service(properties, new ModelHolderService("static-helper"), new ArrayList<>(), true)) {
      service.setPlanAccounts(
          provider(dev.mikoto2000.rei.modeloptions.ChatPlanAccounts.class, accounts));
      service.select("second");
      var owner = owner("independent", AgentRunContext.Mode.EXCLUSIVE);
      var pin = service.acceptIndependent(owner);
      assertThat(pin.profileId()).isEqualTo("_static-chat");
      assertThat(pin.named()).isFalse();
      assertThat(pin.endpoint().baseUrl()).isEqualTo("http://actual-global.invalid/v1");
      assertThat(pin.endpoint().model()).isEqualTo("static-helper");
      assertThat(pin.authentication()).isEqualTo(LlmAuthentication.API_KEY);
      assertThat(pin.accountRef()).isNull();
      assertThat(pin.accountRegistration()).isNull();
      assertThat(
              new com.fasterxml.jackson.databind.ObjectMapper()
                  .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                  .writeValueAsString(pin))
          .doesNotContain("PRIVATE_");
      assertThat(service.accept(owner)).isSameAs(pin);
      service.select("first");
      assertThat(service.acceptIndependent(owner)).isSameAs(pin);
      assertThat(service.selection().endpoint()).isEqualTo("first");
      org.mockito.Mockito.verifyNoInteractions(accounts);
    }
  }

  @ParameterizedTest
  @EnumSource(AgentRunContext.Mode.class)
  void acceptedOptionsKeepTheirProfileAfterTheGlobalSelectionChanges(AgentRunContext.Mode mode) {
    var properties = named("http://first.invalid/v1", "http://second.invalid/v1");
    try (var service =
        service(properties, new ModelHolderService("holder-model"), new ArrayList<>())) {
      var owner = owner("first", mode);
      var pin = service.accept(owner);
      service.select("second");
      assertThat(service.currentEndpoint().profile().id()).isEqualTo("second");
      assertThat(service.accept(owner)).isSameAs(pin);
      assertThat(service.options(owner).getModel()).isEqualTo("first-model");
      assertThat(service.options(owner).getToolContext())
          .containsEntry(
              dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, pin);
      assertThat(pin.generation()).isEmpty();
      assertThat(pin.authentication()).isEqualTo(LlmAuthentication.NONE);
      assertThat(service.accept(owner("next", mode)).profileId()).isEqualTo("second");
    }
  }

  @Test
  void flatFeatureModelIsTheInitialModelEvenWhenTheGlobalHolderDiffers() {
    var properties = new LlmProperties();
    var feature = new LlmProperties.Server();
    feature.setModel("feature-model");
    properties.setFeature(LlmFeature.CHAT, feature);
    try (var service =
        service(properties, new ModelHolderService("holder-model"), new ArrayList<>())) {
      var pin = service.accept(owner("legacy", AgentRunContext.Mode.EXCLUSIVE));
      assertThat(pin.endpoint().model()).isEqualTo("feature-model");
      assertThat(pin.endpoint().baseUrl()).isEqualTo("http://actual-global.invalid/v1");
      assertThat(pin.toString()).doesNotContain("PRIVATE_");
    }
  }

  @Test
  void flatSharedConnectionRetainsTheExistingGlobalModelSelection() {
    try (var service =
        service(new LlmProperties(), new ModelHolderService("holder-model"), new ArrayList<>())) {
      assertThat(service.accept(owner("legacy", AgentRunContext.Mode.EXCLUSIVE)).endpoint().model())
          .isEqualTo("holder-model");
    }
  }

  @Test
  void chatModelChoiceDoesNotMutateTheHolderUsedByHelpers() {
    var holder = new ModelHolderService("helper-model");
    try (var service =
        service(
            named("http://first.invalid", "http://second.invalid"), holder, new ArrayList<>())) {
      service.selectModel("chat-only-model");
      assertThat(service.accept(owner("new", AgentRunContext.Mode.EXCLUSIVE)).endpoint().model())
          .isEqualTo("chat-only-model");
      assertThat(holder.get()).isEqualTo("helper-model");
    }
  }

  @Test
  void restoringAConnectionNeverUsesTheNewSelection() {
    var properties = named("http://first.invalid", "http://second.invalid");
    RunLlmConnection saved;
    try (var first = service(properties, new ModelHolderService("root"), new ArrayList<>())) {
      saved = first.accept(owner("old", AgentRunContext.Mode.EXCLUSIVE));
    }
    try (var resumed = service(properties, new ModelHolderService("root"), new ArrayList<>())) {
      resumed.select("second");
      var owner = owner("resumed", AgentRunContext.Mode.EXCLUSIVE);
      resumed.restore(owner, saved);
      assertThat(resumed.accept(owner)).isEqualTo(saved);
      assertThat(resumed.options(owner).getModel()).isEqualTo("first-model");
    }
  }

  @Test
  void actualScopedInferenceRetainsTheExistingOwnedLlmEvents() throws Exception {
    try (var fixture = new PinnedChatModelsTest.Fixture()) {
      var url = fixture.pin("first", "first-model", LlmAuthentication.NONE).endpoint().baseUrl();
      var events = new ArrayList<AgentEvent>();
      try (var service =
          service(named(url, "http://second.invalid"), new ModelHolderService("root"), events)) {
        var owner = owner("owned", AgentRunContext.Mode.CONVERSATION);
        service.accept(owner);
        assertThat(
                service
                    .model()
                    .call(new Prompt("fixture", service.options(owner)))
                    .getResult()
                    .getOutput()
                    .getText())
            .isEqualTo("fixture-answer");
        assertThat(fixture.headers).containsExactly("<none>");
        assertThat(events)
            .extracting(AgentEvent::type)
            .containsExactly(
                AgentEventType.LLM_REQUEST_STARTED, AgentEventType.LLM_RESPONSE_COMPLETED);
        assertThat(events).allSatisfy(event -> assertThat(event.runId()).isEqualTo(owner.runId()));
      }
    }
  }

  @Test
  void releasedConnectionsCannotBeRecreatedByRequestingOptions() {
    try (var service =
        service(
            named("http://first.invalid", "http://second.invalid"),
            new ModelHolderService("root"),
            new ArrayList<>())) {
      var owner = owner("released", AgentRunContext.Mode.READ_ONLY);
      service.accept(owner);
      service.release(owner);
      service.select("second");
      assertThatThrownBy(() -> service.options(owner)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void selectionViewFollowsTheEndpointAndRemembersEachEndpointsModel() {
    var holder = new ModelHolderService("static-helper");
    try (var service =
        service(
            named("http://first.invalid", "http://second.invalid"), holder, new ArrayList<>())) {
      assertThat(service.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "first", "first-model"));
      service.selectModel("first-picked");
      service.select("second");
      assertThat(service.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "second", "second-model"));
      service.selectModel("second-picked");
      service.select("first");
      assertThat(service.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "first", "first-picked"));
      assertThat(holder.get()).isEqualTo("static-helper");
    }
  }

  @Test
  void legacySelectionViewUsesItsFeatureModelAndThenItsExplicitChatOverride() {
    var properties = new LlmProperties();
    var feature = new LlmProperties.Server();
    feature.setModel("feature-model");
    properties.setFeature(LlmFeature.CHAT, feature);
    var holder = new ModelHolderService("static-helper");
    try (var service = service(properties, holder, new ArrayList<>())) {
      assertThat(service.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "default", "feature-model"));
      service.selectModel("chosen-chat-model");
      assertThat(service.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "default", "chosen-chat-model"));
      assertThat(
              service.accept(owner("selected", AgentRunContext.Mode.EXCLUSIVE)).endpoint().model())
          .isEqualTo("chosen-chat-model");
      assertThat(holder.get()).isEqualTo("static-helper");
    }
  }

  @Test
  void observingSubscriptionSelectionDoesNotRequireLoginOrReadAmbientModelSettings() {
    var properties =
        new Binder(
                new MapConfigurationPropertySource(
                    Map.of(
                        "rei.llm.features.chat.active", "subscription",
                        "rei.llm.features.chat.endpoints.subscription.provider", "chatgpt-plan",
                        "rei.llm.features.chat.endpoints.subscription.api", "responses",
                        "rei.llm.features.chat.endpoints.subscription.account-ref", "personal",
                        "rei.llm.features.chat.endpoints.subscription.model", "configured-model")))
            .bind("rei.llm", LlmProperties.class)
            .get();
    var holder = mock(ModelHolderService.class);
    when(holder.get()).thenThrow(new AssertionError("Selection consulted ambient model"));
    try (var service = service(properties, holder, new ArrayList<>())) {
      assertThat(service.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "subscription", "configured-model"));
    }
  }

  @Test
  void restartRestoresConfiguredEndpointAndModelInsteadOfLastRuntimeChoice() {
    var properties = named("http://first.invalid", "http://second.invalid");
    try (var first = service(properties, new ModelHolderService("helper"), new ArrayList<>())) {
      first.select("second");
      first.selectModel("runtime-model");
      assertThat(first.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "second", "runtime-model"));
    }
    try (var restarted = service(properties, new ModelHolderService("helper"), new ArrayList<>())) {
      assertThat(restarted.selection())
          .isEqualTo(
              new dev.mikoto2000.rei.modeloptions.ChatModelSelection.Selection(
                  "first", "first-model"));
    }
  }

  private ChatConnectionService service(
      LlmProperties properties, ModelHolderService holder, List<AgentEvent> events) {
    return service(properties, holder, events, false);
  }

  private ChatConnectionService service(
      LlmProperties properties,
      ModelHolderService holder,
      List<AgentEvent> events,
      boolean independentFixture) {
    var nativeModel = mock(ChatModel.class);
    if (new ChatEndpointCatalog(properties).hasNamedProfiles() && !independentFixture)
      when(nativeModel.getOptions())
          .thenThrow(new AssertionError("Named chat read ambient model settings"));
    else
      when(nativeModel.getOptions())
          .thenReturn(
              OpenAiChatOptions.builder()
                  .model("native-default")
                  .baseUrl("http://unrelated-native.invalid/v1")
                  .apiKey("PRIVATE_NATIVE")
                  .topP(0.4)
                  .maxRetries(0)
                  .build());
    var models =
        new LlmModelProvider(
            nativeModel, properties, new AgentEventFactory(Clock.systemUTC()), events::add);
    var data = new org.sqlite.SQLiteDataSource();
    data.setUrl("jdbc:sqlite:" + root.resolve("protocol.db"));
    models.configureModelMessagePayloads(
        provider(
            dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore.class,
            new dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore(data)));
    var common = new OpenAiCommonProperties();
    common.setBaseUrl("http://actual-global.invalid");
    common.setApiKey("PRIVATE_GLOBAL");
    return new ChatConnectionService(
        new ChatEndpointCatalog(properties),
        properties,
        models,
        holder,
        provider(OpenAiConnectionDetails.class, null),
        provider(OpenAiCommonProperties.class, common),
        provider(OpenAiChatProperties.class, new OpenAiChatProperties()),
        provider(dev.mikoto2000.rei.llm.capture.CaptureStore.class, null));
  }

  private <T> ObjectProvider<T> provider(Class<T> type, T value) {
    var beans = new StaticListableBeanFactory();
    if (value != null) beans.addBean("value", value);
    return beans.getBeanProvider(type);
  }

  private LlmProperties named(String first, String second) {
    var values = new java.util.HashMap<String, String>();
    values.put("rei.llm.features.chat.active", "first");
    for (var entry : Map.of("first", first, "second", second).entrySet()) {
      var prefix = "rei.llm.features.chat.endpoints." + entry.getKey() + ".";
      values.put(prefix + "base-url", entry.getValue());
      values.put(prefix + "model", entry.getKey() + "-model");
      values.put(prefix + "authentication", "none");
      values.put(prefix + "server-capabilities.chat-completions", "supported");
      values.put(prefix + "server-capabilities.streaming", "supported");
    }
    return new Binder(new MapConfigurationPropertySource(values))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }

  private AgentRunContext owner(String run, AgentRunContext.Mode mode) {
    return new AgentRunContext(
        run, "session", root, "project", AgentRunContext.RequestSource.SHELL, mode, false);
  }
}
