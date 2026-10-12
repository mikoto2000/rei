package dev.mikoto2000.rei.review;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.storage.*;

class ExternalCoverageVerificationTest {
  @TempDir Path dir;
  @Test void opaqueExternalSuccessWithUnknownTargetListIsNeverComplete() throws Exception {
    try(var migration=new StorageMigrationCoordinator(dir)){migration.prepare();}
    var ledger=new ReviewCoverageLedger(new StorageDatabase(dir));
    var scope=new ReviewCoverageLedger.Scope("project","chat","run");
    var job=ledger.createExternal(scope,"external review target: .","external-v1",List.of());
    ledger.externalReport(job.id(),scope,"exitCode=0; all files reviewed");
    assertThat(ledger.get(job.id(),scope).status()).isEqualTo("REVIEW_UNVERIFIED");
    assertThat(ledger.get(job.id(),scope).ranges()).isEmpty();
  }
}
