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
    var nativeModel = mock(ChatModel.class);
    if (new ChatEndpointCatalog(properties).hasNamedProfiles())
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
    return new Binder(
            new MapConfigurationPropertySource(
                Map.of(
                    "rei.llm.features.chat.active", "first",
                    "rei.llm.features.chat.endpoints.first.base-url", first,
                    "rei.llm.features.chat.endpoints.first.model", "first-model",
                    "rei.llm.features.chat.endpoints.first.authentication", "none",
                    "rei.llm.features.chat.endpoints.second.base-url", second,
                    "rei.llm.features.chat.endpoints.second.model", "second-model",
                    "rei.llm.features.chat.endpoints.second.authentication", "none")))
        .bind("rei.llm", LlmProperties.class)
        .get();
  }

  private AgentRunContext owner(String run, AgentRunContext.Mode mode) {
    return new AgentRunContext(
        run, "session", root, "project", AgentRunContext.RequestSource.SHELL, mode, false);
  }
}
