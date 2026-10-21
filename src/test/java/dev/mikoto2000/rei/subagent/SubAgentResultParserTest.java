package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** 補助エージェント結果入力の解釈の結果と状態の契約を検証するテスト。 */
class SubAgentResultParserTest {
  /** 検証に使う入力の解釈。 */
  final SubAgentResultParser parser = new SubAgentResultParser();

  /** 検証に使う有効な。 */
  static final String VALID =
      """
      {"status":"SUCCESS","summary":"done","result":{},"warnings":[]}
      """;

  /** 補助エージェントへの委譲で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void parsesExactlyOneJsonValueWithoutDoingSchemaValidation() {
    assertThat(parser.parse(" \n" + VALID).get("summary").asString()).isEqualTo("done");
    assertThat(parser.parse("[]").isArray()).isTrue();
    assertThat(parser.parse("null").isNull()).isTrue();
  }

  /** 不正なJSON変換を受け付けないことを確認する。 */
  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "not json",
        "Result is: {}",
        "{} done",
        "{} {}",
        "```json\n{}\n```",
        " ",
        "{\"x\":1,\"x\":2}",
        "{\"x\":}",
        "{\"x\":NaN}"
      })
  void rejectsInvalidJsonWithoutRepair(String raw) {
    assertThatThrownBy(() -> parser.parse(raw))
        .isInstanceOf(SubAgentValidationException.class)
        .satisfies(
            error -> assertThat(((SubAgentValidationException) error).errors()).isNotEmpty());
  }

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void limitsSizeAndNestingWithoutExposingRawOutput() {
    String secret = "private-content".repeat(100000);
    assertThatThrownBy(() -> parser.parse("\"" + secret + "\""))
        .isInstanceOf(SubAgentValidationException.class)
        .hasMessageNotContaining("private-content");
    assertThatThrownBy(() -> parser.parse("[".repeat(101) + "0" + "]".repeat(101)))
        .isInstanceOf(SubAgentValidationException.class);
  }
}
