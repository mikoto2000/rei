package dev.mikoto2000.rei.subagent;

import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 補助エージェント結果の入力を解釈し、機能内で扱える情報へ整える。
 *
 * <p>Strict parsing only: no schema checks, extraction, fences, or repair.
 */
public final class SubAgentResultParser {
  /** 最大出力文字数。 */
  public static final int MAX_OUTPUT_CHARS = 1_048_576;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
  private final JsonMapper mapper =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(100)
                          .maxStringLength(MAX_OUTPUT_CHARS)
                          .maxNumberLength(1000)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /** 受け取った入力を解釈し、機能内で扱える情報へ変換する。 */
  public JsonNode parse(String rawOutput) {
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (rawOutput == null || rawOutput.isBlank() || rawOutput.length() > MAX_OUTPUT_CHARS)
      throw invalid();
    try {
      JsonNode json = mapper.readTree(rawOutput);
      if (json == null || json.isMissingNode()) throw invalid();
      return json;
    } catch (JacksonException error) {
      throw invalid();
    }
  }

  /** 受け付けられない指定について、入力の問題を伝える。 */
  private SubAgentValidationException invalid() {
    return new SubAgentValidationException(
        List.of(
            new ValidationError(
                "", "Expected exactly one JSON value within size and nesting limits")));
  }
}
