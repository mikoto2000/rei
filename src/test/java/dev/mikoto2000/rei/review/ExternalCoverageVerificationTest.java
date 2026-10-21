package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageMigrationCoordinator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 外部対象範囲確認結果の結果と状態の契約を検証するテスト。 */
class ExternalCoverageVerificationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 変更内容のレビューで、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void opaqueExternalSuccessWithUnknownTargetListIsNeverComplete() throws Exception {
    try (var migration = new StorageMigrationCoordinator(dir)) {
      migration.prepare();
    }
    var ledger = new ReviewCoverageLedger(new StorageDatabase(dir));
    var scope = new ReviewCoverageLedger.Scope("project", "chat", "run");
    var job = ledger.createExternal(scope, "external review target: .", "external-v1", List.of());
    ledger.externalReport(job.id(), scope, "exitCode=0; all files reviewed");
    assertThat(ledger.get(job.id(), scope).status()).isEqualTo("REVIEW_UNVERIFIED");
    assertThat(ledger.get(job.id(), scope).ranges()).isEmpty();
  }
}
