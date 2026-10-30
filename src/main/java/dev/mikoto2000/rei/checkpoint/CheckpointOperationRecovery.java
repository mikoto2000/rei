package dev.mikoto2000.rei.checkpoint;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Operation;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.OperationStatus;
import java.util.List;
import java.util.Set;

/** 中断したファイル参照と、結果照合が必要な変更操作を区別する。 */
final class CheckpointOperationRecovery {
  /** 再取得で業務上の変更を重複させないファイル参照。未知のツールは含めない。 */
  static final Set<String> RETRYABLE_READS =
      Set.of(
          "readMultiFile",
          "readFile",
          "readPdfFile",
          "readBinaryFile",
          "findFile",
          "listFile",
          "grepMultiQuery",
          "searchAndRead",
          "searchFilePage");

  /** ユーティリティ型の生成を禁止する。 */
  private CheckpointOperationRecovery() {}

  /** 旧形式の UNKNOWN も復旧する。取得できなかった本文を成功や配信済みにはしない。 */
  static Operation interrupted(Operation operation) {
    if (operation.status() != OperationStatus.STARTED
        && operation.status() != OperationStatus.UNKNOWN) return operation;
    var status =
        RETRYABLE_READS.contains(operation.toolName())
            ? OperationStatus.RETRYABLE
            : OperationStatus.UNKNOWN;
    return new Operation(
        operation.toolCallId(),
        operation.toolName(),
        status,
        operation.eventId(),
        operation.runId());
  }

  /** 保存済みの操作を、再開時に扱える状態へ変換する。 */
  static List<Operation> interrupted(List<Operation> operations) {
    return operations.stream().map(CheckpointOperationRecovery::interrupted).toList();
  }
}
