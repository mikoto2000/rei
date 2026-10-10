package dev.mikoto2000.rei.storage;

import java.nio.file.*;
import java.sql.*;
import java.io.IOException;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("integration")
class StorageMigrationCoordinatorTest {
  @TempDir Path root;
  @Test void freshInstallInitializesAndRestartOnlyChecksVersion() throws Exception {
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().schemaVersion()).isEqualTo(1);
      assertThat(migration.prepare().backup()).isNull();
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThat(migration.prepare().backup()).isNull();
    }
    assertThat(version(root.resolve("storage.db"))).isEqualTo(1);
  }
  @Test void legacyDataIsBackedUpVerifiedAndNeverChanged() throws Exception {
    Files.writeString(root.resolve("sessions.json"), "[{\"title\":\"れい\"}]");
    Path turns = Files.createDirectories(root.resolve("state/turns"));
    Files.writeString(turns.resolve("one.json"), "[]");
    Path backup;
    try (var migration = new StorageMigrationCoordinator(root)) { backup = migration.prepare().backup(); }
    assertThat(Files.readString(backup.resolve("files/sessions.json"))).isEqualTo("[{\"title\":\"れい\"}]");
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[{\"title\":\"れい\"}]");
    StorageBackup.verify(backup);
    try (var migration = new StorageMigrationCoordinator(root)) { assertThat(migration.prepare().backup()).isNull(); }
    try (var backups = Files.list(root.resolve(".storage/backups"))) { assertThat(backups.count()).isEqualTo(1); }
  }
  @Test void onlineBackupIncludesCommittedWalRowsAndRestoresToEmptyDirectory() throws Exception {
    Path database = root.resolve("memory-consolidation.db");
    try (var writer = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = writer.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL"); statement.execute("PRAGMA wal_autocheckpoint=0");
      statement.execute("CREATE TABLE facts(id INTEGER PRIMARY KEY,value TEXT)");
      statement.execute("INSERT INTO facts VALUES(1,'WALにしかない行')");
      assertThat(Files.size(Path.of(database + "-wal"))).isPositive();
      Path backup;
      try (var migration = new StorageMigrationCoordinator(root)) { backup = migration.prepare().backup(); }
      try (var copy = DriverManager.getConnection("jdbc:sqlite:" + backup.resolve("files/memory-consolidation.db"));
           var query = copy.createStatement(); var result = query.executeQuery("SELECT value FROM facts")) {
        assertThat(result.next()).isTrue(); assertThat(result.getString(1)).isEqualTo("WALにしかない行");
      }
      Path restored = root.resolve("restored"); StorageBackup.restoreToEmptyDirectory(backup, restored);
      assertThat(restored.resolve("memory-consolidation.db")).exists();
      assertThatThrownBy(() -> StorageBackup.restoreToEmptyDirectory(backup, restored)).isInstanceOf(IOException.class);
    }
  }
  @Test void memoryDatabaseIsSnapshottedBeforeOrdinaryStartupCanAlterItsSchema() throws Exception {
    Path database=root.resolve("memory.db");
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+database);var statement=db.createStatement()) {
      statement.execute("CREATE TABLE SPRING_AI_CHAT_MEMORY(conversation_id TEXT,content TEXT,type TEXT,timestamp TEXT)");
      statement.execute("INSERT INTO SPRING_AI_CHAT_MEMORY VALUES('会話','記憶','USER','2026-01-01')");
    }
    String original=StorageBackup.hash(database);
    Path backup;
    try(var migration=new StorageMigrationCoordinator(root)){backup=migration.prepare().backup();}
    assertThat(backup).isNotNull();
    assertThat(backup.resolve("files/memory.db")).exists();
    assertThat(StorageBackup.hash(database)).isEqualTo(original);
    try(var db=StorageBackup.readOnly(backup.resolve("files/memory.db"));var statement=db.createStatement();
        var rows=statement.executeQuery("SELECT content FROM SPRING_AI_CHAT_MEMORY")) {
      assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("記憶");
    }
  }
  @Test void sqliteHeaderWithoutDatabaseExtensionStillIncludesWalRows() throws Exception {
    Path database=Files.createDirectories(root.resolve("state")).resolve("cache.snapshot");
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+database);var statement=db.createStatement()) {
      statement.execute("PRAGMA journal_mode=WAL");statement.execute("PRAGMA wal_autocheckpoint=0");
      statement.execute("CREATE TABLE facts(value TEXT)");statement.execute("INSERT INTO facts VALUES('拡張子なしの記憶')");
      Path backup;
      try(var migration=new StorageMigrationCoordinator(root)){backup=migration.prepare().backup();}
      try(var copy=StorageBackup.readOnly(backup.resolve("files/state/cache.snapshot"));var query=copy.createStatement();
          var rows=query.executeQuery("SELECT value FROM facts")) {
        assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo("拡張子なしの記憶");
      }
    }
  }
  @Test void newerSchemaIsRejectedWithoutTouchingLegacyData() throws Exception {
    Files.writeString(root.resolve("sessions.json"), "[]");
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("storage.db")); var statement = db.createStatement()) {
      statement.execute("PRAGMA user_version=99");
    }
    try (var migration = new StorageMigrationCoordinator(root)) {
      assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class).hasMessageContaining("newer");
    }
    assertThat(version(root.resolve("storage.db"))).isEqualTo(99);
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
    assertThat(root.resolve(".storage/backups")).doesNotExist();
  }
  @Test void corruptedBackupStopsBeforeSchemaSwitchAndRetryKeepsBothBackups() throws Exception {
    Files.writeString(root.resolve("sessions.json"), "[]");
    try (var migration = new StorageMigrationCoordinator(root, (stage, backup) -> {
      if (stage == StorageMigrationCoordinator.Stage.COPIED) Files.writeString(backup.resolve("files/sessions.json"), "corrupt");
    })) { assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class); }
    assertThat(root.resolve("storage.db")).doesNotExist();
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
    try (var migration = new StorageMigrationCoordinator(root)) { assertThat(migration.prepare().schemaVersion()).isEqualTo(1); }
    try (var backups = Files.list(root.resolve(".storage/backups"))) { assertThat(backups.count()).isEqualTo(2); }
  }
  @Test void sourceChangedDuringBackupCannotBeAcceptedAsConsistent() throws Exception {
    Files.writeString(root.resolve("sessions.json"), "[]");
    try (var migration = new StorageMigrationCoordinator(root, (stage, backup) -> {
      if (stage == StorageMigrationCoordinator.Stage.COPIED) Files.writeString(root.resolve("sessions.json"), "[{}]");
    })) { assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class).hasMessageContaining("changed"); }
    assertThat(root.resolve("storage.db")).doesNotExist();
  }
  @Test void backupChangedAfterVerificationIsRecheckedBeforeSwitch() throws Exception {
    Files.writeString(root.resolve("sessions.json"),"[]");
    try(var migration=new StorageMigrationCoordinator(root,(stage,backup)->{
      if(stage==StorageMigrationCoordinator.Stage.APPLYING)Files.writeString(backup.resolve("files/sessions.json"),"corrupt");
    })){assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class);}
    assertThat(root.resolve("storage.db")).doesNotExist();
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
  }
  @Test void diskFailureAndInterruptedApplyAreRetriedWithoutSuccessMarker() throws Exception {
    Files.writeString(root.resolve("sessions.json"), "[]");
    try (var migration = new StorageMigrationCoordinator(root, (stage, backup) -> {
      if (stage == StorageMigrationCoordinator.Stage.COPIED) throw new IOException("disk full");
    })) { assertThatThrownBy(migration::prepare).hasMessageContaining("disk full"); }
    try (var migration = new StorageMigrationCoordinator(root, (stage, backup) -> {
      if (stage == StorageMigrationCoordinator.Stage.APPLYING) throw new InterruptedException("interrupted");
    })) { assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class); }
    assertThat(root.resolve("storage.db")).doesNotExist();
    try (var migration = new StorageMigrationCoordinator(root)) { assertThat(migration.prepare().schemaVersion()).isEqualTo(1); }
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
  }
  @Test void exclusiveLeaseRejectsAnotherCoordinatorAndReleasesOnClose() throws Exception {
    try (var first = new StorageMigrationCoordinator(root)) {
      first.prepare();
      assertThatThrownBy(() -> new StorageMigrationCoordinator(root)).isInstanceOf(IOException.class).hasMessageContaining("another");
    }
    try (var next = new StorageMigrationCoordinator(root)) { assertThat(next.prepare().schemaVersion()).isEqualTo(1); }
  }
  @Test void failureAfterSchemaWritesRollsBackBeforeRetry() throws Exception {
    Files.writeString(root.resolve("sessions.json"),"[]");
    try(var migration=new StorageMigrationCoordinator(root,(stage,backup)->{
      if(stage.name().equals("SCHEMA_WRITTEN"))throw new SQLException("injected SQLite write failure");
    })){assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class);}
    assertThat(version(root.resolve("storage.db"))).isZero();
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var query=db.createStatement();
        var rows=query.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name='storage_migrations'")) {
      rows.next();assertThat(rows.getInt(1)).isZero();
    }
    try(var migration=new StorageMigrationCoordinator(root)){assertThat(migration.prepare().schemaVersion()).isEqualTo(1);}
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
  }
  @Test void backupMetadataIsBoundedEvenWhenItsJsonRemainsValid() throws Exception {
    Files.writeString(root.resolve("sessions.json"), "[]");Path backup;
    try(var migration=new StorageMigrationCoordinator(root)){backup=migration.prepare().backup();}
    Path marker=backup.resolve("verified.json");
    Files.writeString(marker," ".repeat(70000)+Files.readString(marker));
    assertThatThrownBy(()->StorageBackup.verify(backup)).isInstanceOf(IOException.class).hasMessageContaining("metadata");
  }
  @Test void orphanWalIsNotSilentlyOmittedFromBackup() throws Exception {
    Path state=Files.createDirectories(root.resolve("state"));Files.writeString(state.resolve("missing.db-wal"),"unresolved");
    try(var migration=new StorageMigrationCoordinator(root)){
      assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class).hasMessageContaining("orphan");
    }
    assertThat(root.resolve("storage.db")).doesNotExist();
  }
  @Test void copyWriteFailureDoesNotInitializeOrModifySource() throws Exception {
    Files.writeString(root.resolve("sessions.json"),"[]");
    try(var migration=new StorageMigrationCoordinator(root,(stage,backup)->{
      if(stage==StorageMigrationCoordinator.Stage.COPYING)Files.createDirectories(backup.resolve("files/sessions.json"));
    })){assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class);}
    assertThat(root.resolve("storage.db")).doesNotExist();
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
  }
  @Test void unknownUnversionedDatabaseIsNeverOverwritten() throws Exception {
    try(var db=DriverManager.getConnection("jdbc:sqlite:"+root.resolve("storage.db"));var query=db.createStatement()) {
      query.execute("CREATE TABLE foreign_data(value TEXT)");query.execute("INSERT INTO foreign_data VALUES('preserve')");
    }
    String before=StorageBackup.hash(root.resolve("storage.db"));
    try(var migration=new StorageMigrationCoordinator(root)){assertThatThrownBy(migration::prepare).hasMessageContaining("Unknown tables");}
    assertThat(StorageBackup.hash(root.resolve("storage.db"))).isEqualTo(before);
    assertThat(version(root.resolve("storage.db"))).isZero();
  }
  @Test void forcedProcessTerminationReleasesLeaseAndRetriesVerifiedButUnappliedBackup() throws Exception {
    Files.writeString(root.resolve("sessions.json"),"[]");
    String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
    Path arguments=root.resolve("child-jvm.args");
    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Files.writeString(arguments,"-cp\n"+javaArgument(classpath)+"\n"+StorageMigrationCrashWorker.class.getName()+"\n"+javaArgument(root.toString())+"\n");
    var child=new ProcessBuilder(executable,"@"+arguments).redirectErrorStream(true).start();
    try {
      var ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{
        try(var reader=child.inputReader()){String line;while((line=reader.readLine())!=null)if(line.equals("APPLYING"))return true;return false;}
        catch(IOException error){throw new java.io.UncheckedIOException(error);}
      });
      assertThat(ready.get(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThatThrownBy(()->new StorageMigrationCoordinator(root)).isInstanceOf(IOException.class).hasMessageContaining("another");
    }finally{child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    assertThat(root.resolve("storage.db")).exists();
    assertThat(Files.readString(root.resolve(".storage/migration-state.json"))).contains("APPLYING");
    try(var migration=new StorageMigrationCoordinator(root)){assertThat(migration.prepare().schemaVersion()).isEqualTo(1);}
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
    try(var backups=Files.list(root.resolve(".storage/backups"))){assertThat(backups.count()).isEqualTo(2);}
  }
  @Test void hotJournalFromOwnedInterruptedMigrationIsRecoveredBeforeVersionProbe() throws Exception {
    Files.writeString(root.resolve("sessions.json"),"[]");
    try(var migration=new StorageMigrationCoordinator(root,(stage,backup)->{
      if(stage==StorageMigrationCoordinator.Stage.APPLYING)throw new IOException("interrupt before database creation");
    })){assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class);}
    var child=startHotJournalWorker();
    try {
      var ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{
        try(var reader=child.inputReader()){String line;while((line=reader.readLine())!=null)if(line.equals("SPILLED"))return true;return false;}
        catch(IOException error){throw new java.io.UncheckedIOException(error);}
      });
      assertThat(ready.get(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }finally{child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    assertThat(root.resolve("storage.db-journal")).exists();
    // The ordinary read-only probe cannot resolve this hot journal on its own.
    assertThatThrownBy(()->{
      try(var db=StorageBackup.readOnly(root.resolve("storage.db"));var query=db.createStatement()){query.executeQuery("PRAGMA user_version");}
    }).isInstanceOf(SQLException.class);
    try(var migration=new StorageMigrationCoordinator(root)){assertThat(migration.prepare().schemaVersion()).isEqualTo(1);}
    assertThat(Files.readString(root.resolve("sessions.json"))).isEqualTo("[]");
    try(var db=StorageBackup.readOnly(root.resolve("storage.db"));var query=db.createStatement();
        var rows=query.executeQuery("SELECT count(*) FROM sqlite_schema WHERE name='partial'")){rows.next();assertThat(rows.getInt(1)).isZero();}
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings={"missing-state","corrupt-backup","future-schema"})
  void unsafeHotJournalRecoveryIsRefusedWithoutModifyingDatabaseOrJournal(String kind)throws Exception {
    Files.writeString(root.resolve("sessions.json"),"[]");var backup=new java.util.concurrent.atomic.AtomicReference<Path>();
    try(var migration=new StorageMigrationCoordinator(root,(stage,path)->{
      if(stage==StorageMigrationCoordinator.Stage.APPLYING){backup.set(path);throw new IOException("interrupt");}
    })){assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class);}
    var child=startHotJournalWorker();
    try {
      var ready=java.util.concurrent.CompletableFuture.supplyAsync(()->{
        try(var reader=child.inputReader()){String line;while((line=reader.readLine())!=null)if(line.equals("SPILLED"))return true;return false;}
        catch(IOException error){throw new java.io.UncheckedIOException(error);}
      });assertThat(ready.get(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }finally{child.destroyForcibly();assertThat(child.waitFor(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    if(kind.equals("missing-state"))Files.delete(root.resolve(".storage/migration-state.json"));
    if(kind.equals("corrupt-backup"))Files.writeString(backup.get().resolve("files/sessions.json"),"corrupt");
    if(kind.equals("future-schema"))try(var file=new java.io.RandomAccessFile(root.resolve("storage.db").toFile(),"rw")){file.seek(60);file.writeInt(99);}
    String databaseHash=StorageBackup.hash(root.resolve("storage.db")),journalHash=StorageBackup.hash(root.resolve("storage.db-journal"));
    try(var migration=new StorageMigrationCoordinator(root)){assertThatThrownBy(migration::prepare).isInstanceOf(IOException.class);}
    assertThat(StorageBackup.hash(root.resolve("storage.db"))).isEqualTo(databaseHash);
    assertThat(StorageBackup.hash(root.resolve("storage.db-journal"))).isEqualTo(journalHash);
  }
  private Process startHotJournalWorker()throws Exception {
    Path arguments=root.resolve("hot-journal-child.args");
    String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
    Files.writeString(arguments,"-cp\n"+javaArgument(classpath)+"\n"+StorageMigrationCrashWorker.class.getName()+"\n"+javaArgument(root.toString())+"\nhot-journal\n");
    return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"@"+arguments)
        .redirectErrorStream(true).start();
  }
  private static String javaArgument(String value){return "\""+value.replace("\\","\\\\").replace("\"","\\\"")+"\"";}
  static int version(Path path) throws Exception {
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + path); var statement = db.createStatement();
         var result = statement.executeQuery("PRAGMA user_version")) { result.next(); return result.getInt(1); }
  }
}
