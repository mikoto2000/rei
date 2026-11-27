package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChatRunConnectionsTest {
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.CsvSource({
    "chat-completions,UNKNOWN,false", "chat-completions,UNSUPPORTED,false",
    "streaming,UNKNOWN,false", "streaming,UNSUPPORTED,false",
    "chat-completions,UNKNOWN,true", "chat-completions,UNSUPPORTED,true",
    "streaming,UNKNOWN,true", "streaming,UNSUPPORTED,true"
  })
  void namedChatAdmissionAndRestorationRequireConfirmedApiAndStreaming(
      String capability, LlmEndpointCapabilities.Support support, boolean restoring) {
    var connections = declaredChatConnections(restoring ? null : capability, support);
    var owner = owner("candidate", "session");
    if (restoring) {
      var original =
          declaredChatConnections(null, LlmEndpointCapabilities.Support.SUPPORTED)
              .accept(owner("original", "session"));
      var capabilities = new HashMap<>(original.capabilities());
      capabilities.put(
          LlmEndpointCapabilities.Capability.valueOf(
              capability.toUpperCase(java.util.Locale.ROOT).replace('-', '_')),
          support);
      var saved =
          new RunLlmConnection(
              original.profileId(),
              original.named(),
              original.provider(),
              original.authentication(),
              original.accountRef(),
              original.accountRegistration(),
              original.endpoint(),
              capabilities,
              original.generation(),
              original.transport());
      assertThatThrownBy(() -> connections.restore(owner, saved))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(capability.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    } else {
      assertThatThrownBy(() -> connections.accept(owner))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining(capability.toUpperCase(java.util.Locale.ROOT).replace('-', '_'));
    }
    assertThatThrownBy(() -> connections.require(owner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  private ChatRunConnections declaredChatConnections(
      String changed, LlmEndpointCapabilities.Support support) {
    var server = new LlmProperties.Server();
    server.setBaseUrl("http://owned-fixture.invalid/v1");
    server.setModel("fixture-model");
    server.setAuthentication("none");
    var capabilities = new HashMap<String, String>();
    capabilities.put("chat-completions", "supported");
    capabilities.put("streaming", "supported");
    if (changed != null) capabilities.put(changed, support.name());
    server.setServerCapabilities(capabilities);
    var chat = new LlmProperties.Server();
    chat.setActive("fixture");
    chat.setEndpoints(java.util.Map.of("fixture", server));
    var properties = new LlmProperties();
    properties.setFeature(LlmFeature.CHAT, chat);
    var catalog = new ChatEndpointCatalog(properties);
    return new ChatRunConnections(
        catalog,
        new RunLlmConnectionFactory(
            catalog,
            properties,
            ignored -> {
              throw new AssertionError("No fallback");
            },
            ignored -> null));
  }

  @Test
  void continuationWithoutAnAcceptedConnectionMustStopInsteadOfSamplingSelection() {
    var connections = connections();
    assertThatThrownBy(() -> connections.require(owner("not-accepted", "session")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void releaseDoesNotAllowLaterContinuationToCreateANewBinding() {
    var connections = connections();
    var owner = owner("run", "session");
    connections.accept(owner);
    connections.release(owner);
    connections.select("second");
    assertThatThrownBy(() -> connections.require(owner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void restoredConnectionIgnoresTheNewProcessesSelectedEndpoint() {
    var original = connections().accept(owner("old-run", "session"));
    var restarted = connections();
    restarted.select("second");
    var resumed = owner("resumed-run", "session");
    restarted.restore(resumed, original);
    assertThat(restarted.require(resumed)).isEqualTo(original);
    assertThat(restarted.selectedEndpoint()).isEqualTo("second");
  }

  @Test
  void restoreCannotReplaceAnotherAcceptedConnection() {
    var connections = connections();
    var owner = owner("run", "session");
    var original = connections.accept(owner);
    connections.select("second");
    var different = connections.accept(owner("other-run", "session"));
    assertThatThrownBy(() -> connections.restore(owner, different))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("replaced");
    assertThat(connections.require(owner)).isSameAs(original);
  }

  @Test
  void deletedProfileCannotBeRestoredIntoTheCurrentDefault() {
    var original = connections().accept(owner("old-run", "session"));
    var restarted = connections("second");
    assertThatThrownBy(() -> restarted.restore(owner("resumed", "session"), original))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint-name");
  }

  @TempDir Path root;

  @Test
  void changingSelectionOnlyAffectsNewRunsAndNeverResamplesAnAcceptedRun() {
    var connections = connections();
    var first = owner("run-one", "session");
    var accepted = connections.accept(first);
    connections.select("second");
    assertThat(connections.selectedEndpoint()).isEqualTo("second");
    assertThat(connections.accept(first)).isSameAs(accepted);
    assertThat(accepted.profileId()).isEqualTo("first");
    assertThat(connections.accept(owner("run-two", "session")).profileId()).isEqualTo("second");
  }

  @Test
  void modelOverridesBelongToEachEndpointAndDoNotChangeAcceptedModels() {
    var connections = connections();
    var original = connections.accept(owner("run-zero", "session"));
    connections.selectModel("first-override");
    assertThat(connections.accept(owner("run-one", "session")).endpoint().model())
        .isEqualTo("first-override");
    connections.select("second");
    connections.selectModel("second-override");
    assertThat(connections.accept(owner("run-two", "session")).endpoint().model())
        .isEqualTo("second-override");
    connections.select("first");
    assertThat(connections.accept(owner("run-three", "session")).endpoint().model())
        .isEqualTo("first-override");
    assertThat(original.endpoint().model()).isEqualTo("first-model");
  }

  @Test
  void unknownEndpointCannotChangeSelectionOrFallBack() {
    var connections = connections();
    assertThatThrownBy(() -> connections.select("PRIVATE_CANARY"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint-name")
        .hasMessageNotContaining("PRIVATE_CANARY");
    assertThat(connections.selectedEndpoint()).isEqualTo("first");
  }

  @Test
  void anotherSessionCannotReuseTheSameRunConnection() {
    var connections = connections();
    connections.accept(owner("same-run", "original-session"));
    assertThatThrownBy(() -> connections.accept(owner("same-run", "other-session")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("owner");
  }

  @Test
  void restartRestoresConfiguredSelectionRatherThanLastRuntimeChoice() {
    var first = connections();
    first.select("second");
    var restarted = connections();
    assertThat(first.selectedEndpoint()).isEqualTo("second");
    assertThat(restarted.selectedEndpoint()).isEqualTo("first");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = LlmEndpointCapabilities.Support.class,
      names = {"UNKNOWN", "UNSUPPORTED"})
  void unavailableResponsesCannotPublishAnAcceptedRun(LlmEndpointCapabilities.Support support) {
    var connections = responsesConnections(support);
    var owner = owner("unavailable", "session");
    assertThatThrownBy(() -> connections.accept(owner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RESPONSES");
    assertThatThrownBy(() -> connections.require(owner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @Test
  void explicitlyDeclaredResponsesCanBeAcceptedWithoutOpeningAClient() {
    var connections = responsesConnections(LlmEndpointCapabilities.Support.SUPPORTED);
    var owner = owner("available", "session");
    var accepted = connections.accept(owner);
    assertThat(accepted.endpoint().apiType()).isEqualTo(LlmApiType.RESPONSES);
    assertThat(connections.require(owner)).isSameAs(accepted);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = LlmEndpointCapabilities.Support.class,
      names = {"UNKNOWN", "UNSUPPORTED"})
  void restoredResponsesAlsoRequiresAnAcceptedProtocolCapability(
      LlmEndpointCapabilities.Support support) {
    var original =
        responsesConnections(LlmEndpointCapabilities.Support.SUPPORTED)
            .accept(owner("original", "session"));
    var capabilities = new HashMap<>(original.capabilities());
    capabilities.put(LlmEndpointCapabilities.Capability.RESPONSES, support);
    var saved =
        new RunLlmConnection(
            original.profileId(),
            original.named(),
            original.provider(),
            original.authentication(),
            original.accountRef(),
            original.accountRegistration(),
            original.endpoint(),
            capabilities,
            original.generation(),
            original.transport());
    var restarted = responsesConnections(LlmEndpointCapabilities.Support.SUPPORTED);
    var resumed = owner("resumed", "session");
    assertThatThrownBy(() -> restarted.restore(resumed, saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("RESPONSES");
    assertThatThrownBy(() -> restarted.require(resumed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unavailable");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.EnumSource(
      value = LlmEndpointCapabilities.Support.class,
      names = {"UNKNOWN", "UNSUPPORTED"})
  void normalChatRequiresStreamingBeforeAdmissionOrRestore(
      LlmEndpointCapabilities.Support support) {
    var connections = responsesConnections(LlmEndpointCapabilities.Support.SUPPORTED, support);
    var owner = owner("unavailable-stream", "session");
    assertThatThrownBy(() -> connections.accept(owner))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STREAMING");
    assertThatThrownBy(() -> connections.require(owner)).isInstanceOf(IllegalStateException.class);
    var source =
        responsesConnections(LlmEndpointCapabilities.Support.SUPPORTED)
            .accept(owner("original", "session"));
    var capabilities = new HashMap<>(source.capabilities());
    capabilities.put(LlmEndpointCapabilities.Capability.STREAMING, support);
    var saved =
        new RunLlmConnection(
            source.profileId(),
            source.named(),
            source.provider(),
            source.authentication(),
            source.accountRef(),
            source.accountRegistration(),
            source.endpoint(),
            capabilities,
            source.generation(),
            source.transport());
    var restarted = responsesConnections(LlmEndpointCapabilities.Support.SUPPORTED);
    assertThatThrownBy(() -> restarted.restore(owner, saved))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("STREAMING");
    assertThatThrownBy(() -> restarted.require(owner)).isInstanceOf(IllegalStateException.class);
  }

  private ChatRunConnections responsesConnections(LlmEndpointCapabilities.Support support) {
    return responsesConnections(support, LlmEndpointCapabilities.Support.SUPPORTED);
  }

  private ChatRunConnections responsesConnections(
      LlmEndpointCapabilities.Support support, LlmEndpointCapabilities.Support streaming) {
    var server = new LlmProperties.Server();
    server.setBaseUrl("http://owned-fixture.invalid/v1");
    server.setApi(LlmApiType.RESPONSES);
    server.setAuthentication("none");
    server.setModel("fixture-model");
    server.setReasoningReplay(ReasoningReplayMode.NONE);
    server.setServerCapabilities(
        java.util.Map.of("responses", support.name(), "streaming", streaming.name()));
    var chat = new LlmProperties.Server();
    chat.setActive("responses");
    chat.setEndpoints(java.util.Map.of("responses", server));
    var properties = new LlmProperties();
    properties.setFeature(LlmFeature.CHAT, chat);
    var catalog = new ChatEndpointCatalog(properties);
    return new ChatRunConnections(
        catalog,
        new RunLlmConnectionFactory(
            catalog,
            properties,
            ignored -> {
              throw new AssertionError("No legacy fallback");
            },
            ignored -> null));
  }

  private AgentRunContext owner(String run, String session) {
    return new AgentRunContext(run, session, root, "project");
  }

  private ChatRunConnections connections() {
    return connections("first", "second");
  }

  private ChatRunConnections connections(String... names) {
    var values = new HashMap<String, String>();
    values.put("rei.llm.features.chat.active", names[0]);
    for (var name : names) {
      var prefix = "rei.llm.features.chat.endpoints." + name + ".";
      values.put(prefix + "base-url", "http://" + name + ".invalid");
      values.put(prefix + "authentication", "none");
      values.put(prefix + "model", name + "-model");
    }
    var props =
        new Binder(new MapConfigurationPropertySource(values))
            .bind("rei.llm", LlmProperties.class)
            .get();
    var catalog = new ChatEndpointCatalog(props);
    return new ChatRunConnections(
        catalog,
        new RunLlmConnectionFactory(
            catalog,
            props,
            ignored -> {
              throw new AssertionError("No legacy fallback");
            },
            ignored -> null));
  }
}
