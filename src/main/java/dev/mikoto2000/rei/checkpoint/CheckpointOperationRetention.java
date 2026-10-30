package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Evidence;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Operation;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.OperationStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 未確定操作と再開証拠を保護し、確定済み操作の作業用一覧を有界に保つ。 */
final class CheckpointOperationRetention {
  /** スナップショット内の従来の操作上限。 */
  private static final int LIMIT = 500;

  /** 再開コンテキストが走査する直近の操作数。 */
  private static final int RECENT = 64;

  /** ユーティリティ型の生成を禁止する。 */
  private CheckpointOperationRetention() {}

  /** 古い成功・再取得可能記録を作業用一覧から外す。結果不明・失敗操作、直近の操作、現行証拠が参照する操作は必ず保持する。 */
  static List<Operation> retain(List<Operation> operations, List<Evidence> evidence) {
    int excess = operations.size() - LIMIT;
    if (excess <= 0) return List.copyOf(operations);
    var retained = new ArrayList<Operation>();
    int recentStart = Math.max(0, operations.size() - RECENT);
    for (int index = 0; index < operations.size(); index++) {
      var operation = operations.get(index);
      if (excess > 0
          && index < recentStart
          && (operation.status() == OperationStatus.SUCCEEDED
              || operation.status() == OperationStatus.RETRYABLE)
          && evidence.stream().noneMatch(item -> references(item, operation))) {
        excess--;
      } else {
        retained.add(operation);
      }
    }
    if (excess > 0)
      throw new IllegalStateException(
          "Checkpoint operation capacity reached; inspect unresolved operations before proceeding");
    return List.copyOf(retained);
  }

  /** 旧形式の実行 ID が欠ける証拠も、同じ呼び出しを保守的に保護する。 */
  private static boolean references(Evidence evidence, Operation operation) {
    return (evidence.eventId() != null && evidence.eventId().equals(operation.eventId()))
        || (evidence.toolCallId() != null
            && evidence.toolCallId().equals(operation.toolCallId())
            && (evidence.runId() == null || Objects.equals(evidence.runId(), operation.runId())));
  }
}
