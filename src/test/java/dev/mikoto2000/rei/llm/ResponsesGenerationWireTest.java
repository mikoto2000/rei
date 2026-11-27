package dev.mikoto2000.rei.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.prompt.Prompt;

/** SDK serialization only; declared capabilities do not establish live model compatibility. */
class ResponsesGenerationWireTest {
  @ParameterizedTest
  @CsvSource({
    "false,reasoning_effort",
    "true,reasoning_effort",
    "false,response_format",
    "true,response_format"
  })
  void configuredGenerationUsesResponsesFieldsOnEitherAuthenticationRoute(
      boolean plan, String field) {
    Object value = field.equals("reasoning_effort") ? "low" : Map.of("type", "json_object");
    try (var fixture = new ResponsesLoopFixture(plan, Map.of(), Map.of(field, value))) {
      fixture.replies = turn -> fixture.reply;
      var options = fixture.options();
      var model = ResponsesAdapter.createForRun(fixture.pin, options, fixture.client);
      assertThat(
              model.stream(new Prompt("fixture", options))
                  .collectList()
                  .block(java.time.Duration.ofSeconds(5))
                  .getLast()
                  .getResult()
                  .getOutput()
                  .getText())
          .isEqualTo("done");
      assertThat(fixture.requests).hasSize(1);
      var wire = fixture.requests.getFirst();
      assertThat(wire.has("reasoning_effort")).isFalse();
      assertThat(wire.has("response_format")).isFalse();
      if (field.equals("reasoning_effort"))
        assertThat(wire.at("/reasoning/effort").asText()).isEqualTo("low");
      else assertThat(wire.at("/text/format/type").asText()).isEqualTo("json_object");
      if (plan) {
        assertThat(wire.path("store").asBoolean(true)).isFalse();
        assertThat(wire.path("stream").asBoolean()).isTrue();
        assertThat(wire.has("max_output_tokens")).isFalse();
      } else assertThat(wire.path("max_output_tokens").asInt()).isEqualTo(64);
    }
  }
}
