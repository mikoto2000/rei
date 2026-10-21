package dev.mikoto2000.rei.websearch;

import dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pure validation before binding, authorization, or web access. */
public final class WebSearchArguments {
  private static final int MAX_INPUT_CHARS = 16384;
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(16)
                          .maxStringLength(MAX_INPUT_CHARS)
                          .maxNumberLength(1000)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private WebSearchArguments() {}

  public static boolean supports(String tool) {
    return "webSearchAndRead".equals(tool);
  }

  public static void validate(String tool, String input, int configuredMaxResults) {
    if (!supports(tool)) return;
    if (configuredMaxResults < 1 || configuredMaxResults > 20)
      throw new IllegalStateException("Invalid web search maxResults configuration");
    var problems = new ArrayList<String>();
    JsonNode root;
    try {
      if (input == null || input.isBlank() || input.length() > MAX_INPUT_CHARS)
        throw new IllegalArgumentException();
      root = JSON.readTree(input);
      if (root == null || !root.isObject()) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) {
      throw invalid("引数はサイズ・深さ制限内の単一JSONオブジェクトにしてください。重複キーや末尾の追加データは使用できません。");
    }
    var request = root.path("request");
    if (!request.isObject())
      throw invalid(
          "request オブジェクトが必要です。例: {\"request\":{\"query\":\"Void full-stack framework\"}}");
    var query = request.path("query");
    if (!query.isString() || query.asString().isBlank() || query.asString().trim().length() > 2048)
      problems.add("query must contain 1..2048 characters");
    Integer maxResults = integer(request, "maxResults", problems);
    Integer readTop = integer(request, "readTop", problems);
    problems.addAll(rangeProblems(maxResults, readTop, configuredMaxResults));
    var refresh = request.path("forceRefresh");
    if (!refresh.isMissingNode() && !refresh.isNull() && !refresh.isBoolean())
      problems.add("forceRefresh must be a boolean or null");
    if (!problems.isEmpty()) throw invalid(String.join("\n", problems));
  }

  private static Integer integer(JsonNode request, String name, List<String> problems) {
    var value = request.path(name);
    if (value.isMissingNode() || value.isNull()) return null;
    if (!value.isIntegralNumber() || !value.canConvertToInt()) {
      problems.add(name + " must be an integer or null; do not quote numbers");
      return null;
    }
    return value.intValue();
  }

  private static List<String> rangeProblems(
      Integer maxResults, Integer readTop, int configuredMaxResults) {
    int effectiveMax = maxResults == null ? configuredMaxResults : maxResults;
    var problems = new ArrayList<String>();
    if (effectiveMax < 1 || effectiveMax > configuredMaxResults)
      problems.add("maxResults must be between 1 and " + configuredMaxResults);
    if (readTop != null && (readTop < 0 || readTop > effectiveMax))
      problems.add("readTop must be between 0 and maxResults (" + effectiveMax + ")");
    return problems;
  }

  private static InvalidToolArgumentsException invalid(String guidance) {
    return new InvalidToolArgumentsException(
        "webSearchAndRead: "
            + guidance
            + "\nrequest 内の引数を修正してください。maxResults は省略または null で設定上限、readTop は省略または null で min(3, maxResults) です。検索・本文取得は実行していません。");
  }
}
