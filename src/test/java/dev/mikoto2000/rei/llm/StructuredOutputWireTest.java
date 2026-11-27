package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.prompt.Prompt;

/** Actual SDK serialization; these declarations establish no live model compatibility. */
class StructuredOutputWireTest {
  private static final String SCHEMA =
      """
      {"type":"object","properties":{"answer":{"type":"string"}},
       "required":["answer"],"additionalProperties":false}
      """;

  static Stream<Arguments> chatRoutes() {
    return Stream.of(LlmAuthentication.NONE, LlmAuthentication.API_KEY)
        .flatMap(auth -> Stream.of(false, true).map(strict -> Arguments.of(auth, strict)));
  }

  @ParameterizedTest
  @MethodSource("chatRoutes")
  void chatSchemaAndStrictnessReachTheWire(LlmAuthentication auth, boolean strict)
      throws Exception {
    try (var fixture = new PinnedChatModelsTest.Fixture();
        var models = new PinnedChatModels(pin -> "fixture-key", pin -> {}, null, 2)) {
      var base = fixture.pin("schema", "fixture-model", auth);
      var generation = new java.util.HashMap<>(base.generation());
      generation.put("response_format", format(strict));
      var pin =
          new RunLlmConnection(
              base.profileId(),
              true,
              base.provider(),
              auth,
              null,
              null,
              base.endpoint(),
              Map.of(
                  LlmEndpointCapabilities.Capability.STRUCTURED_OUTPUT,
                  LlmEndpointCapabilities.Support.SUPPORTED),
              generation);
      models.model(pin).call(new Prompt("fixture", RunNativeOptions.options(pin)));
      assertThat(fixture.bodies).hasSize(1);
      assertThat(fixture.headers)
          .containsExactly(auth == LlmAuthentication.NONE ? "<none>" : "Bearer fixture-key");
      var sent = fixture.bodies.getFirst().path("response_format");
      assertThat(sent.path("type").asText()).isEqualTo("json_schema");
      assertSchema(sent.path("json_schema"), strict);
    }
  }

  static Stream<Arguments> responsesRoutes() {
    return Stream.of(false, true)
        .flatMap(plan -> Stream.of(false, true).map(strict -> Arguments.of(plan, strict)));
  }

  @ParameterizedTest
  @MethodSource("responsesRoutes")
  void responsesSchemaAndStrictnessReachBothAuthenticationRoutes(boolean plan, boolean strict) {
    try (var fixture =
        new ResponsesLoopFixture(plan, Map.of(), Map.of("response_format", format(strict)))) {
      fixture.replies = turn -> fixture.reply;
      var options = fixture.options();
      ResponsesAdapter.createForRun(fixture.pin, options, fixture.client).stream(
              new Prompt("fixture", options))
          .collectList()
          .block(java.time.Duration.ofSeconds(5));
      assertThat(fixture.requests).hasSize(1);
      var sent = fixture.requests.getFirst();
      assertThat(sent.has("response_format")).isFalse();
      assertThat(sent.at("/text/format/type").asText()).isEqualTo("json_schema");
      assertSchema(sent.at("/text/format"), strict);
      assertThat(fixture.credentialReads.get()).isEqualTo(plan ? 1 : 0);
      if (plan) {
        assertThat(sent.path("store").asBoolean(true)).isFalse();
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.has("max_output_tokens")).isFalse();
      }
    }
  }

  private static Map<String, Object> format(boolean strict) {
    return Map.of("type", "json_schema", "json_schema", SCHEMA, "strict", strict);
  }

  private static void assertSchema(com.fasterxml.jackson.databind.JsonNode format, boolean strict) {
    assertThat(format.path("name").asText()).isEqualTo("json_schema");
    assertThat(format.path("strict").isBoolean()).isTrue();
    assertThat(format.path("strict").asBoolean()).isEqualTo(strict);
    var schema = format.path("schema");
    assertThat(schema.path("type").asText()).isEqualTo("object");
    assertThat(schema.at("/properties/answer/type").asText()).isEqualTo("string");
    assertThat(schema.path("required").get(0).asText()).isEqualTo("answer");
    assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
  }
}
