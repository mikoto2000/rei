package dev.mikoto2000.rei.toolcontract;

import java.io.IOException;

/** A bounded file result cannot retain its required metadata; the caller may narrow the request. */
public final class FileResultBudgetExceededException extends IOException {
  public static final String CODE = "file_result_budget_exceeded";
  public static final String MESSAGE =
      "検索・読み取り結果が出力上限を超えました。baseDir・includeGlob・検索語で対象を絞り、maxFiles・contextLinesを減らして再試行してください。";

  public FileResultBudgetExceededException() {
    super(
        "File result metadata exceeds byte/token budget; narrow the request or increase its budget");
  }
}
