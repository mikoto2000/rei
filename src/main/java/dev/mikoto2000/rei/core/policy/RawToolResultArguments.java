package dev.mikoto2000.rei.core.policy;

import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ツール利用の権限制御に必要な元のツールの結果引数を扱う。
 *
 * <p>Validates raw-result cursor shapes before argument binding or stored-result access.
 */
public final class RawToolResultArguments {
  /** 最大入力文字数。 */
  private static final int MAX_INPUT_CHARS = 1_048_576;

  /** 保存や受け渡しに用いるデータ形式の変換。 */
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

  /** 継続入力。 */
  private static final String CONTINUATION =
      " offset は必須です。初回は offset: 0、続きは前回応答の nextOffset の数値を offset に指定してください。"
          + " nextOffset は出力項目であり入力名ではありません。数値を引用符で囲まないでください。"
          + " rawResultRef は同じ値を維持し、hasMore=false なら読み終わりです。保存結果の読み込みは実行していません。";

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private RawToolResultArguments() {}

  /** ツール利用の権限制御において、元のツールの結果引数への対応可否を返す。 */
  public static boolean supports(String tool) {
    return "readRawToolResult".equals(tool);
  }

  /** 利用条件に合わない指定を受け付けないよう、入力内容を検証する。 */
  public static void validate(String tool, String input) {
    if (!supports(tool)) return;
    JsonNode root;
    try {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (input == null || input.isBlank() || input.length() > MAX_INPUT_CHARS)
        throw new IllegalArgumentException();
      root = JSON.readTree(input);
      if (root == null || !root.isObject()) throw new IllegalArgumentException();
    } catch (RuntimeException invalid) {
      throw new InvalidToolArgumentsException(
          "readRawToolResult: サイズ・深さ制限内の単一JSONオブジェクトを指定してください。"
              + " 重複キーや末尾の追加データは使用できません。"
              + CONTINUATION);
    }
    var problems = new ArrayList<String>();
    var reference = root.path("rawResultRef");
    if (!reference.isString() || reference.asString().isBlank())
      problems.add("rawResultRef: required nonempty string from the saved result reference");
    integer(root, "offset", 0, Integer.MAX_VALUE, problems);
    integer(root, "limit", 1, 16000, problems);
    if (root.has("nextOffset"))
      problems.add("nextOffset: output-only field; pass its numeric value as offset instead");
    if (!problems.isEmpty())
      throw new InvalidToolArgumentsException(
          "Invalid readRawToolResult arguments; correct all listed problems together:\n"
              + String.join("\n", problems)
              + "\n"
              + CONTINUATION);
  }

  /** ツール利用の権限制御に関する整数を処理する。 */
  private static void integer(
      JsonNode root, String name, int minimum, int maximum, List<String> problems) {
    var value = root.path(name);
    if (!value.isIntegralNumber()
        || !value.canConvertToInt()
        || value.intValue() < minimum
        || value.intValue() > maximum)
      problems.add(
          name
              + ": required integer from "
              + minimum
              + " to "
              + maximum
              + " (not a string or null)");
  }
}
