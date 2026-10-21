package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 保存Io観測内容の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class StorageIoObservationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** データ保存の共通処理で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void countsSuccessfulExplicitForcesAndRestoresNestedObservation() throws Exception {
    Path file = Files.writeString(root.resolve("fixture"), "fixture");
    var outer = new StorageIoObservation();
    var inner = new StorageIoObservation();
    try (var scope = outer.observe()) {
      StorageBackup.force(file);
      try (var nested = inner.observe()) {
        StorageBackup.force(file);
      }
      assertThatThrownBy(() -> StorageBackup.force(root.resolve("missing")))
          .isInstanceOf(java.io.IOException.class);
      StorageBackup.force(file);
    }
    StorageBackup.force(file);
    assertThat(outer.forces.sum()).isEqualTo(2);
    assertThat(inner.forces.sum()).isEqualTo(1);
  }
}
