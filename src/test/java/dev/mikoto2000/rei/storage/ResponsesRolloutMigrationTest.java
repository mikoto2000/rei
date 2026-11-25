package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.chatmemory.JdbcConversationMemoryStore;
import dev.mikoto2000.rei.checkpoint.CheckpointProperties;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpoint;
import dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.datasource.ReiPaths;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.modelmessage.ModelMessagePayloadStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.sqlite.SQLiteDataSource;

/** Synthetic old data, real stores/migrator/verified backups; no production data or API calls. */
@Tag("integration")
class ResponsesRolloutMigrationTest {
  @TempDir Path root;

  private record Stores(
      AnnotationConfigApplicationContext context,
      ModelMessagePayloadStore payloads,
      PersistentCheckpointRepository checkpoints,
      JdbcConversationMemoryStore memory)
      implements AutoCloseable {
    @Override
    public void close() {
      context.close();
    }
  }

  private record Fixture(
      Path data,
      ModelMessagePayloadStore.Scope scope,
      ModelMessagePayloadStore.Ref ref,
      ModelMessagePayloadStore.History history,
      List<org.springframework.ai.chat.messages.part.MessagePart> parts,
      PersistentCheckpoint legacy) {}

  private SQLiteDataSource source(Path file) {
    var source = new SQLiteDataSource();
    source.setUrl("jdbc:sqlite:" + file);
    return source;
  }

  private Stores stores(Path data) {
    var source = source(ReiPaths.memoryConsolidationDbPath(data));
    var context = new AnnotationConfigApplicationContext();
    context.registerBean(
        ModelMessagePayloadStore.class, () -> new ModelMessagePayloadStore(source));
    context.registerBean(
        PersistentCheckpointRepository.class,
        () -> new PersistentCheckpointRepository(source, new CheckpointProperties()));
    context.refresh();
    return new Stores(
        context,
        context.getBean(ModelMessagePayloadStore.class),
        context.getBean(PersistentCheckpointRepository.class),
        new JdbcConversationMemoryStore(source(ReiPaths.memoryDbPath(data))));
  }

  private Fixture schema19WithBothPendingProtocols() throws Exception {
    Path data = Files.createDirectory(root.resolve("source"));
    Path work = Files.createDirectory(root.resolve("owned-project"));
    try (var migration = new StorageMigrationCoordinator(data)) {
      migration.prepare();
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("storage.db"));
        var sql = db.createStatement()) {
      StorageSchemaFixture.removeDurableSchema(sql);
      sql.execute("DELETE FROM storage_migrations WHERE version>=20");
      sql.execute("PRAGMA user_version=19");
      sql.execute(
          "INSERT INTO review_jobs VALUES('owned-legacy','project','legacy-session','2026-11-25','owned preserved review')");
    }
    new ResourceDatabasePopulator(
            new ClassPathResource("dev/mikoto2000/rei/conversation/schema-sqlite.sql"))
        .execute(source(ReiPaths.memoryDbPath(data)));
    try (var stores = stores(data)) {
      stores
          .memory()
          .saveAll(
              "legacy-session",
              List.of(
                  new UserMessage("owned existing CC question"),
                  new AssistantMessage("owned existing CC answer")));
      var legacy =
          PersistentCheckpoint.initial(
              "legacy-pending",
              "project",
              "legacy-session",
              "legacy-run",
              work,
              "owned unfinished CC task");
      stores.checkpoints().save(legacy, 0, "before-native-opt-in");
      var owner = new AgentRunContext("native-run", "native-session", work, "project");
      var exchange = stores.payloads().newExchange(owner);
      var origin =
          new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "owned-native-endpoint");
      var assistant =
          AssistantMessage.builder()
              .parts(
                  List.of(
                      new ReasoningPart(
                          null,
                          null,
                          new OpaquePayload("openai", "reasoning", "owned synthetic ciphertext"),
                          Map.of("id", "rs_owned", "status", "completed")),
                      new TextPart("owned native pending result", null, Map.of())))
              .properties(
                  Map.of(
                      "rei.llm.replay-origin-owner",
                      origin.owner(),
                      "rei.llm.replay-origin-endpoint",
                      origin.endpoint()))
              .build();
      var ref =
          stores.payloads().save(exchange.scope(), owner.runId(), "assistant/ASSISTANT", assistant);
      stores
          .payloads()
          .publish(
              exchange.scope(), ref, ModelMessagePayloadStore.ReferenceKind.RUN, owner.runId());
      var history =
          new ModelMessagePayloadStore.History(
              1,
              owner.runId(),
              origin,
              List.of(new ModelMessagePayloadStore.History.Entry("assistant/ASSISTANT", ref)));
      var fields =
          stores
              .checkpoints()
              .fields(
                  PersistentCheckpoint.initial(
                      "native-pending",
                      "project",
                      "native-session",
                      owner.runId(),
                      work,
                      "owned unfinished native task"));
      fields.put(
          "progress", new RunExecutionContext.ProgressState(null, null, 0, 0, 0, null, history));
      stores.checkpoints().save(stores.checkpoints().fields(fields), 0, "before-upgrade");
      stores
          .payloads()
          .release(
              exchange.scope(), ref, ModelMessagePayloadStore.ReferenceKind.RUN, owner.runId());
      try (var db =
              DriverManager.getConnection(
                  source(ReiPaths.memoryConsolidationDbPath(data)).getUrl());
          var sql = db.createStatement()) {
        sql.execute("UPDATE model_message_payloads SET staged_until=0");
      }
      assertThat(stores.payloads().collectExpired()).isZero();
      return new Fixture(data, exchange.scope(), ref, history, assistant.getParts(), legacy);
    }
  }

  private void verifyPreserved(Path data, Fixture fixture) throws Exception {
    try (var stores = stores(data)) {
      assertThat(stores.memory().findByConversationId("legacy-session"))
          .extracting(Message::getText)
          .containsExactly("owned existing CC question", "owned existing CC answer");
      var legacy = stores.checkpoints().get("project", "legacy-pending");
      assertThat(legacy.request()).isEqualTo(fixture.legacy().request());
      assertThat(legacy.progress()).isEqualTo(fixture.legacy().progress());
      assertThat(legacy.status()).isEqualTo("RUNNING");
      var nativeTask = stores.checkpoints().get("project", "native-pending");
      assertThat(nativeTask.status()).isEqualTo("RUNNING");
      assertThat(nativeTask.progress().protocolHistory()).isEqualTo(fixture.history());
      assertThat(stores.payloads().collectExpired()).isZero();
      var restored = stores.payloads().load(fixture.scope(), fixture.ref());
      assertThat(restored).isInstanceOf(AssistantMessage.class);
      assertThat(((AssistantMessage) restored).getParts())
          .containsExactlyElementsOf(fixture.parts());
    }
    try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("storage.db"));
        var sql = db.createStatement();
        var row = sql.executeQuery("SELECT record FROM review_jobs WHERE id='owned-legacy'")) {
      assertThat(row.getString(1)).isEqualTo("owned preserved review");
    }
  }

  @Test
  void upgradeIdempotenceAndVerifiedRestorePreserveCcAndNativePendingHistoryAndGcPins()
      throws Exception {
    var fixture = schema19WithBothPendingProtocols();
    Path backup;
    try (var migration = new StorageMigrationCoordinator(fixture.data())) {
      var result = migration.prepare();
      assertThat(result.schemaVersion()).isEqualTo(StorageMigrationCoordinator.SCHEMA_VERSION);
      backup = result.backup();
      assertThat(backup).isNotNull();
    }
    verifyPreserved(fixture.data(), fixture);
    try (var migration = new StorageMigrationCoordinator(fixture.data())) {
      assertThat(migration.prepare().backup()).isNull();
    }
    StorageBackup.verify(backup);
    Path restored = root.resolve("restored");
    StorageBackup.restoreToEmptyDirectory(backup, restored);
    verifyPreserved(restored, fixture);
    try (var migration = new StorageMigrationCoordinator(restored)) {
      assertThat(migration.prepare().schemaVersion())
          .isEqualTo(StorageMigrationCoordinator.SCHEMA_VERSION);
    }
    verifyPreserved(restored, fixture);
  }

  @Test
  void rollbackRejectsOverwritingNewDataAndRejectsCorruptBackupBeforeCreatingItsTarget()
      throws Exception {
    var fixture = schema19WithBothPendingProtocols();
    Path backup;
    try (var migration = new StorageMigrationCoordinator(fixture.data())) {
      backup = migration.prepare().backup();
    }
    var occupied = Files.createDirectory(root.resolve("new-data"));
    var sentinel = occupied.resolve("retain.txt");
    Files.writeString(sentinel, "owned later data");
    assertThatThrownBy(() -> StorageBackup.restoreToEmptyDirectory(backup, occupied))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("empty");
    assertThat(Files.readString(sentinel)).isEqualTo("owned later data");
    Files.writeString(
        backup.resolve("files/storage.db"),
        "owned corruption",
        java.nio.file.StandardOpenOption.APPEND);
    Path rejected = root.resolve("rejected-restore");
    assertThatThrownBy(() -> StorageBackup.restoreToEmptyDirectory(backup, rejected))
        .isInstanceOf(java.io.IOException.class);
    assertThat(rejected).doesNotExist();
    verifyPreserved(fixture.data(), fixture);
  }
}
