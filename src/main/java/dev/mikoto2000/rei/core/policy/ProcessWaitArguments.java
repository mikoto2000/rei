package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.dependency.DependencyAwaiter;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ツール利用の権限制御に必要なプロセス待機引数を扱う。
 *
 * <p>Pure timeout validation before approval, process inspection, or waiting.
 */
public final class ProcessWaitArguments {
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

  /** 案内。 */
  private static final String GUIDANCE =
      "waitForShellProcess: timeoutSeconds は 0〜"
          + DependencyAwaiter.MAX_TIMEOUT_SECONDS
          + " 秒の整数を指定してください。"
          + " 0 は即時の状態確認、省略または null は既定の10秒です。"
          + " プロセスの確認・待機は実行していません。同じ processId で待機時間だけを修正してください。"
          + " WAITING なら同じ processId で上限内の待機を再度指定できます。プロセスを起動し直さないでください。";

  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private ProcessWaitArguments() {}

  /** ツール利用の権限制御において、プロセス待機引数への対応可否を返す。 */
  public static boolean supports(String tool) {
    return "waitForShellProcess".equals(tool);
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
          "waitForShellProcess: 引数はサイズ・深さ制限内の単一JSONオブジェクトにしてください。"
              + " 重複キーや末尾の追加データは使用できません。"
              + GUIDANCE);
    }
    var timeout = root.path("timeoutSeconds");
    if (timeout.isMissingNode() || timeout.isNull()) return;
    if (!timeout.isIntegralNumber()
        || !timeout.canConvertToInt()
        || timeout.intValue() < 0
        || timeout.intValue() > DependencyAwaiter.MAX_TIMEOUT_SECONDS)
      throw new InvalidToolArgumentsException(GUIDANCE);
  }
}
