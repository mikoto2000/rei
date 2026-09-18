package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class SubAgentResultParserTest {
  final SubAgentResultParser parser = new SubAgentResultParser();
  static final String VALID = """
      {"status":"SUCCESS","summary":"done","result":{},"warnings":[]}
      """;

  @Test void parsesExactlyOneJsonValueWithoutDoingSchemaValidation() {
    assertThat(parser.parse(" \n" + VALID).get("summary").asString()).isEqualTo("done");
    assertThat(parser.parse("[]").isArray()).isTrue();
    assertThat(parser.parse("null").isNull()).isTrue();
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"not json", "Result is: {}", "{} done", "{} {}", "```json\n{}\n```", " ",
      "{\"x\":1,\"x\":2}", "{\"x\":}", "{\"x\":NaN}"})
  void rejectsInvalidJsonWithoutRepair(String raw) {
    assertThatThrownBy(() -> parser.parse(raw)).isInstanceOf(SubAgentValidationException.class)
        .satisfies(error -> assertThat(((SubAgentValidationException) error).errors()).isNotEmpty());
  }

  @Test void limitsSizeAndNestingWithoutExposingRawOutput() {
    String secret = "private-content".repeat(100000);
    assertThatThrownBy(() -> parser.parse("\"" + secret + "\""))
        .isInstanceOf(SubAgentValidationException.class).hasMessageNotContaining("private-content");
    assertThatThrownBy(() -> parser.parse("[".repeat(101) + "0" + "]".repeat(101)))
        .isInstanceOf(SubAgentValidationException.class);
  }
}
