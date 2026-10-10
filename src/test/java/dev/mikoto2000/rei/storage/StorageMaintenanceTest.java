package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.time.*;
import java.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class StorageMaintenanceTest {
  @TempDir Path root;StorageObjectRegistry registry;StorageMaintenance maintenance;
  @BeforeEach void prepare()throws Exception {try(var gate=new StorageMigrationCoordinator(root)){gate.prepare();}registry=new StorageObjectRegistry(root);maintenance=new StorageMaintenance(registry);}
  @Test void observationIsReadOnlyAndIncrementalVacuumDoesNotChangeLegacyMode()throws Exception {
    var before=maintenance.status();assertThat(before.databaseBytes()).isPositive();assertThat(before.pageSize()).isPositive();assertThat(before.usableDiskBytes()).isPositive();
    assertThat(maintenance.incrementalVacuum(100)).isFalse();assertThat(maintenance.status().autoVacuum()).isEqualTo(before.autoVacuum());assertThatThrownBy(()->maintenance.incrementalVacuum(0)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void passiveCheckpointReportsAnOngoingReaderAndVacuumCanReclaimFreePagesInExplicitWindow()throws Exception {
    registry.database.transaction(db->{try(var query=db.createStatement()){query.execute("CREATE TABLE maintenance_fixture(value BLOB)");query.execute("INSERT INTO maintenance_fixture VALUES(zeroblob(4194304))");}return null;});
    try(var reader=StorageBackup.readOnly(root.resolve("storage.db"));var query=reader.createStatement();var rows=query.executeQuery("SELECT value FROM maintenance_fixture")) {
      assertThat(rows.next()).isTrue();registry.database.transaction(db->{try(var write=db.createStatement()){write.execute("DELETE FROM maintenance_fixture");}return null;});
      var checkpoint=maintenance.checkpoint();assertThat(checkpoint.logFrames()).isGreaterThan(checkpoint.checkpointedFrames());
      assertThatThrownBy(()->maintenance.vacuum(Duration.ofSeconds(30))).isInstanceOf(IllegalStateException.class);
    }
    var before=maintenance.status();assertThat(before.freePages()).isPositive();maintenance.vacuum(Duration.ofSeconds(30));assertThat(maintenance.status().pageCount()).isLessThan(before.pageCount());
  }
  @Test void incrementalModeIsPreservedAndReclaimsOnlyABoundedBatch()throws Exception {
    Path separate=Files.createDirectories(root.resolve("incremental-fixture"));
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+separate.resolve("storage.db"));var query=db.createStatement()){query.execute("PRAGMA auto_vacuum=INCREMENTAL");}
    try(var gate=new StorageMigrationCoordinator(separate)){gate.prepare();}var objects=new StorageObjectRegistry(separate);var maintenance=new StorageMaintenance(objects);
    objects.database.transaction(db->{try(var query=db.createStatement()){query.execute("CREATE TABLE free_fixture(value BLOB)");query.execute("INSERT INTO free_fixture VALUES(zeroblob(4194304))");query.execute("DELETE FROM free_fixture");}return null;});
    var before=maintenance.status();assertThat(before.autoVacuum()).isEqualTo(2);assertThat(maintenance.incrementalVacuum(10)).isTrue();var after=maintenance.status();assertThat(after.autoVacuum()).isEqualTo(2);assertThat(before.freePages()-after.freePages()).isBetween(1L,10L);
  }
}
