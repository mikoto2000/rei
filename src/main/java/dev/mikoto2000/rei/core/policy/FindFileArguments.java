package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.FindFileRequest;
import java.util.ArrayList;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** findFile の引数を、オブジェクト変換・承認・ファイル検索より前に検証する。 */
public final class FindFileArguments {
  /** 最大入力文字数。 */
  private static final int MAX_INPUT_CHARS = 1_048_576;

  /** 重複キーや過大な入力を受け付けない JSON 解析器。 */
  private static final JsonMapper JSON =
      JsonMapper.builder(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder()
                          .maxNestingDepth(32)
                          .maxStringLength(MAX_INPUT_CHARS)
                          .maxNumberLength(1000)
                          .build())
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  /** 修正に必要な入力形式と、未実行であることの案内。 */
  private static final String GUIDANCE =
      "findFile: request.keywords は空白以外の文字列を1件以上含む配列を指定してください。"
          + " request.maxResults は1〜"
          + FindFileRequest.MAX_RESULTS
          + "の整数を指定してください。省略または null は既定の50件です。"
          + " ファイル検索は実行していません。引数を修正して再度呼び出してください。";

  /** 外部からのインスタンス化を防ぐ。 */
  private FindFileArguments() {}

  /** 引数補正の対象となるツールか判定する。 */
  public static boolean supports(String tool) {
    return "findFile".equals(tool);
  }

  /** 対象ツールの引数だけを検証し、修正可能な不備は専用例外で返す。 */
  public static void validate(String tool, String input) {
    if (!supports(tool)) return;
    JsonNode root;
    try {
      if (input == null || input.isBlank() || input.length() > MAX_INPUT_CHARS)
        throw new IllegalArgumentException();
      root = JSON.readTree(input);
      if (root == null || !root.isObject()) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) {
      throw new InvalidToolArgumentsException(
          "サイズ・深さ制限内の単一JSONオブジェクトを指定してください。" + " 重複キーや末尾の追加データは使用できません。 " + GUIDANCE);
    }
    var request = root.path("request");
    if (!request.isObject()) throw new InvalidToolArgumentsException(GUIDANCE);
    var problems = new ArrayList<String>();
    var keywords = request.path("keywords");
    boolean nonBlank = false;
    if (keywords.isArray()) {
      for (var keyword : keywords) {
        if (keyword.isString() && !keyword.asString().isBlank()) nonBlank = true;
        else if (!keyword.isNull() && !keyword.isString())
          problems.add("keywords entries must be strings or null");
      }
    }
    if (!nonBlank) problems.add("keywords must contain at least one non-blank string");
    var limit = request.path("maxResults");
    if (!limit.isMissingNode()
        && !limit.isNull()
        && (!limit.isIntegralNumber()
            || !limit.canConvertToInt()
            || limit.intValue() < 1
            || limit.intValue() > FindFileRequest.MAX_RESULTS))
      problems.add("maxResults must be an integer between 1 and " + FindFileRequest.MAX_RESULTS);
    if (!problems.isEmpty())
      throw new InvalidToolArgumentsException(String.join("\n", problems) + "\n" + GUIDANCE);
  }
}
