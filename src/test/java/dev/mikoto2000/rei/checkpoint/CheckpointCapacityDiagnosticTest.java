package dev.mikoto2000.rei.checkpoint;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CheckpointCapacityDiagnosticTest {
  @Test void storageFailureExplainsEveryReachedLimitAndRecoveryCommands() {
    var failure=CheckpointException.storageCapacity(10,10,1000,25,1024);
    assertThat(failure.code()).isEqualTo(CheckpointException.Code.CAPACITY);
    assertThat(failure.capacityDetail().settingNames()).isEqualTo(List.of("rei.checkpoint.max-revisions","rei.checkpoint.max-bytes"));
    assertThat(failure.getMessage()).contains("1000 / 1024", "25 バイト", "10 / 10", "rei.checkpoint.max-bytes",
        "rei.checkpoint.max-revisions", "/checkpoint status", "/checkpoint cleanup --apply", "application.yaml",
        "再起動", "/resume list", "/resume <taskId>", "承認待ち", "以前の履歴は保持");
  }

  @Test void byteLimitDoesNotSuggestIncreasingAnUnreachedRevisionLimit() {
    var failure=CheckpointException.storageCapacity(2,10,1000,25,1024);
    assertThat(failure.capacityDetail().settingNames()).containsExactly("rei.checkpoint.max-bytes");
    assertThat(failure.getMessage()).doesNotContain("rei.checkpoint.max-revisions");
  }

  @Test void singleSnapshotHasItsOwnLimitAndCannotBeRepairedByPruningOtherSnapshots() {
    var failure=CheckpointException.snapshotCapacity(2048,1024);
    assertThat(failure.capacityDetail().settingNames()).containsExactly("rei.checkpoint.max-snapshot-bytes");
    assertThat(failure.getMessage()).contains("2048 / 1024", "rei.checkpoint.max-snapshot-bytes", "再起動",
        "全体の履歴整理では 1 件の容量は小さくなりません");
  }
}
