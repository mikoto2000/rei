package dev.mikoto2000.rei.core.taskstate;

import dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 作業状態の記録に必要なタスク状態引数を扱う。
 *
 * <p>Pure, bounded preflight before approval or deserialization; never guesses how to split a
 * string.
 */
public final class TaskStateArguments {
  /** 外部から直接インスタンス化させないためのコンストラクター。 */
  private TaskStateArguments() {}

  /** 最大入力文字数。 */
  private static final int MAX_INPUT_CHARS = 1_048_576;

  /** 受け付ける状態の一覧。 */
  private static final Set<String> STATUSES =
      Set.of(
          TaskState.STATUS_IDLE,
          TaskState.STATUS_IN_PROGRESS,
          TaskState.STATUS_COMPLETED,
          TaskState.STATUS_BLOCKED);

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

  /** 作業状態の記録において、タスク状態引数への対応可否を返す。 */
  public static boolean supports(String tool) {
    return "updateTaskState".equals(tool);
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
          "updateTaskState arguments: expected exactly one JSON object within size and nesting limits; duplicate keys are not allowed");
    }
    var problems = new ArrayList<String>();
    var goal = root.path("goal");
    if (!goal.isMissingNode() && !goal.isNull() && !goal.isString())
      problems.add("goal: must be a string, null, or omitted");
    var status = root.path("status");
    if (!status.isString() || !STATUSES.contains(status.asString()))
      problems.add("status: required string; choose IDLE, IN_PROGRESS, COMPLETED, or BLOCKED");
    array(root, "completed", problems);
    array(root, "pending", problems);
    if (!problems.isEmpty())
      throw new InvalidToolArgumentsException(
          "Invalid updateTaskState arguments; correct all listed problems together:\n"
              + String.join("\n", problems));
  }

  /** 作業状態の記録に関するarrayを処理する。 */
  private static void array(JsonNode root, String field, List<String> problems) {
    var value = root.path(field);
    if (value.isMissingNode() || value.isNull()) return;
    if (!value.isArray()) {
      problems.add(
          field
              + ": must be a JSON array of strings, for example [\"review\",\"verify\"]. Use [] or omit when empty; do not send a comma-separated string or a JSON-encoded array string");
      return;
    }
    int index = 0, invalid = 0;
    for (var item : value) {
      // The existing TaskState implementation ignores null items; retain that compatibility.
      if (!item.isString() && !item.isNull()) {
        if (invalid < 8)
          problems.add(field + "[" + index + "]: must be a string or null (ignored)");
        invalid++;
      }
      index++;
    }
    if (invalid > 8)
      problems.add(
          field + ": " + (invalid - 8) + " additional items must be strings or null (ignored)");
  }
}
