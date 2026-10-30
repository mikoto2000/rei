package dev.mikoto2000.rei.checkpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.Operation;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint.OperationStatus;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 再取得可能な参照の境界と、未知の副作用を保護する復旧規則を検証する。 */
class CheckpointOperationRecoveryTest {
  /** 結果不明の参照だけを再取得可能にし、確定済み結果は書き換えない。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "readMultiFile",
        "readFile",
        "readPdfFile",
        "readBinaryFile",
        "findFile",
        "listFile",
        "grepMultiQuery",
        "searchAndRead",
        "searchFilePage"
      })
  void interruptedReadsCanResumeWithoutConfirmation(String tool) {
    for (var status : List.of(OperationStatus.STARTED, OperationStatus.UNKNOWN)) {
      var read = new Operation("read", tool, status, "event", "old-run");
      var operations = CheckpointOperationRecovery.interrupted(List.of(read));
      assertEquals(
          new Operation("read", tool, OperationStatus.RETRYABLE, "event", "old-run"),
          operations.getFirst());
      assertEquals(operations, CheckpointOperationRecovery.interrupted(operations));
    }
    for (var status :
        List.of(OperationStatus.PLANNED, OperationStatus.SUCCEEDED, OperationStatus.FAILED)) {
      var operation = new Operation("read", tool, status, "event", "old-run");
      assertEquals(operation, CheckpointOperationRecovery.interrupted(operation));
    }
  }

  /** 名前が参照風でも、明示的に定義していない操作は再実行可能と推定しない。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "writeMultiFile",
        "runCommand",
        "httpRequestWrite",
        "reviewStatus",
        "readReviewFile",
        "readAndDelete",
        "sendReader",
        "customReadTool"
      })
  void unknownEffectsRemainUnknown(String tool) {
    var operation = new Operation("call", tool, OperationStatus.STARTED, "event", "run");
    assertEquals(
        OperationStatus.UNKNOWN, CheckpointOperationRecovery.interrupted(operation).status());
  }

  /** 再取得可能記録の蓄積は上限を埋めず、直近の再取得情報と結果不明の変更は残す。 */
  @Test
  void oldRetryableReadsCanBeCompactedWithoutDiscardingUnknownWrites() {
    var operations = new ArrayList<Operation>();
    for (int i = 0; i < 500; i++)
      operations.add(
          new Operation(
              "read-" + i, "readMultiFile", OperationStatus.RETRYABLE, "event-" + i, "run"));
    var write = new Operation("write", "deploy", OperationStatus.UNKNOWN, "write-event", "run");
    operations.add(write);
    var retained = CheckpointOperationRetention.retain(operations, List.of());
    assertEquals(500, retained.size());
    assertTrue(retained.contains(write));
    assertTrue(retained.contains(operations.get(499)));
    assertFalse(retained.contains(operations.getFirst()));
  }
}
