package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/** Actual SDK requests to owned loopback/pre-DNS fixtures only; never a live provider. */
class NamedFunctionCapabilityTest {
  static Stream<Arguments> blockedChatRequests() {
    return Stream.of(
            LlmEndpointCapabilities.Support.UNKNOWN, LlmEndpointCapabilities.Support.UNSUPPORTED)
        .flatMap(support ->
            Stream.of(LlmAuthentication.NONE, LlmAuthentication.API_KEY)
                .flatMap(auth ->
                    Stream.of(false, true).map(stream -> Arguments.of(support, auth, stream))));
  }

  static Stream<Arguments> blockedResponsesRequests() {
    return Stream.of(
            LlmEndpointCapabilities.Support.UNKNOWN, LlmEndpointCapabilities.Support.UNSUPPORTED)
        .flatMap(support ->
            Stream.of(false, true)
                .flatMap(plan ->
                    Stream.of(false, true).map(stream -> Arguments.of(support, plan, stream))));
  }

  static Stream<Arguments> confirmedChatRequests() {
    return Stream.of(LlmAuthentication.NONE, LlmAuthentication.API_KEY)
        .flatMap(auth -> Stream.of(false, true).map(stream -> Arguments.of(auth, stream)));
  }

  @ParameterizedTest
  @MethodSource("blockedChatRequests")
  void unconfirmedChatToolsStopBeforeHttpOrCredentialAccess(
      LlmEndpointCapabilities.Support support, LlmAuthentication authentication, boolean streaming)
      throws Exception {
    var reads = new AtomicInteger();
    try (var fixture = new PinnedChatModelsTest.Fixture();
        var models =
            new PinnedChatModels(
                pin -> {
                  reads.incrementAndGet();
                  return "fixture-only-key";
                },
                pin -> {},
                null,
                2)) {
      var connection =
          capability(fixture.pin("function", "fixture-model", authentication), support);
      var options =
          ((OpenAiChatOptions) RunNativeOptions.options(connection))
              .mutate()
              .toolCallbacks(List.of(callback()))
              .build();
      assertThatThrownBy(
              () -> infer(models.model(connection), new Prompt("fixture", options), streaming))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("FUNCTION_CALLING");
      assertThat(fixture.bodies).isEmpty();
      assertThat(reads).hasValue(0);
    }
  }

  @ParameterizedTest
  @MethodSource("blockedResponsesRequests")
  void responsesToolsUseTheSameIndependentCapabilityBeforeHttpOrCredentials(
      LlmEndpointCapabilities.Support support, boolean plan, boolean streaming) {
    try (var fixture = new ResponsesLoopFixture(plan)) {
      fixture.replies = ignored -> fixture.reply;
      var connection = capability(fixture.pin, support);
      var context = new HashMap<>(fixture.options().getToolContext());
      context.put(
          dev.mikoto2000.rei.modeloptions.ModelOptions.RUN_CONNECTION_CONTEXT_KEY, connection);
      var options =
          fixture
              .options()
              .mutate()
              .toolContext(null)
              .toolContext(context)
              .toolCallbacks(List.of(callback()))
              .build();
      var model = ResponsesAdapter.createForRun(connection, options, fixture.client);
      assertThatThrownBy(() -> infer(model, new Prompt("fixture", options), streaming))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("FUNCTION_CALLING");
      assertThat(fixture.requests).isEmpty();
      assertThat(fixture.credentialReads).hasValue(0);
    }
  }

  @ParameterizedTest
  @MethodSource("confirmedChatRequests")
  void confirmedChatToolsKeepTheirTypedWireContractAndAuthentication(
      LlmAuthentication authentication, boolean streaming) throws Exception {
    var reads = new AtomicInteger();
    try (var fixture = new PinnedChatModelsTest.Fixture();
        var models =
            new PinnedChatModels(
                pin -> {
                  reads.incrementAndGet();
                  return "fixture-only-key";
                },
                pin -> {},
                null,
                2)) {
      var connection =
          capability(
              fixture.pin("function", "fixture-model", authentication),
              LlmEndpointCapabilities.Support.SUPPORTED);
      var options =
          ((OpenAiChatOptions) RunNativeOptions.options(connection))
              .mutate()
              .toolCallbacks(List.of(callback()))
              .build();
      infer(models.model(connection), new Prompt("fixture", options), streaming);
      assertThat(fixture.bodies).hasSize(1);
      var tool = fixture.bodies.getFirst().at("/tools/0");
      assertThat(tool.path("type").asText()).isEqualTo("function");
      assertThat(tool.at("/function/name").asText()).isEqualTo("fixture_read");
      assertThat(tool.at("/function/parameters/type").asText()).isEqualTo("object");
      assertThat(fixture.headers)
          .containsExactly(
              authentication == LlmAuthentication.NONE ? "<none>" : "Bearer fixture-only-key");
      assertThat(reads).hasValue(authentication == LlmAuthentication.NONE ? 0 : 1);
    }
  }

  private static RunLlmConnection capability(
      RunLlmConnection base, LlmEndpointCapabilities.Support support) {
    var capabilities = new HashMap<>(base.capabilities());
    capabilities.put(LlmEndpointCapabilities.Capability.FUNCTION_CALLING, support);
    return new RunLlmConnection(
        base.profileId(),
        base.named(),
        base.provider(),
        base.authentication(),
        base.accountRef(),
        base.accountRegistration(),
        base.endpoint(),
        capabilities,
        base.generation(),
        base.transport());
  }

  private static void infer(ChatModel model, Prompt prompt, boolean streaming) {
    if (streaming) model.stream(prompt).collectList().block(Duration.ofSeconds(5));
    else model.call(prompt);
  }

  private static ToolCallback callback() {
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("fixture_read")
            .description("Owned fixture tool")
            .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
            .build();
      }

      public String call(String input) {
        throw new AssertionError("SDK fixture must not execute a host tool");
      }
    };
  }
}
