package dev.mikoto2000.rei.subagent;

import java.util.List;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;

/** Strict parsing only: no schema checks, extraction, fences, or repair. */
public final class SubAgentResultParser {
  public static final int MAX_OUTPUT_CHARS = 1_048_576;
  private final JsonMapper mapper = JsonMapper.builder(JsonFactory.builder()
      .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(100)
          .maxStringLength(MAX_OUTPUT_CHARS).maxNumberLength(1000).build())
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

  public JsonNode parse(String rawOutput) {
    if (rawOutput == null || rawOutput.isBlank() || rawOutput.length() > MAX_OUTPUT_CHARS) throw invalid();
    try {
      JsonNode json = mapper.readTree(rawOutput);
      if (json == null || json.isMissingNode()) throw invalid();
      return json;
    } catch (JacksonException error) { throw invalid(); }
  }
  private SubAgentValidationException invalid() {
    return new SubAgentValidationException(List.of(new ValidationError("", "Expected exactly one JSON value within size and nesting limits")));
  }
}
