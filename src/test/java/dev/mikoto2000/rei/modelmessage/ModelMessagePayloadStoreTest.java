package dev.mikoto2000.rei.modelmessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.part.OpaquePayload;
import org.springframework.ai.chat.messages.part.ReasoningPart;
import org.springframework.ai.chat.messages.part.TextPart;
import org.springframework.ai.chat.messages.part.ToolCallPart;
import org.sqlite.SQLiteDataSource;

class ModelMessagePayloadStoreTest {
  @TempDir Path temporary;
  private final ModelMessagePayloadStore.Scope owner = new ModelMessagePayloadStore.Scope("project", "root", "conversation");

  static class Time extends java.time.Clock {
    java.time.Instant now = java.time.Instant.parse("2026-11-24T00:00:00Z");
    @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
    @Override public java.time.Clock withZone(java.time.ZoneId zone) { return this; }
    @Override public java.time.Instant instant() { return now; }
    void expire() { now = now.plusSeconds(61); }
  }

  ModelMessagePayloadStore store(Time time, long runBytes, long totalBytes) {
    return new ModelMessagePayloadStore(source(), time,
        new ModelMessagePayloadStore.Limits(runBytes, totalBytes, java.time.Duration.ofSeconds(60)));
  }

  @Test
  void aSeparateJvmReadsTheSameDurablePartsAfterTheWriterHasExited() throws Exception {
    Path references = temporary.resolve("reference.txt");
    runProcess(WriterProcess.class, temporary.resolve("payloads.db").toString(), references.toString());
    var ref = java.nio.file.Files.readAllLines(references);
    runProcess(ReaderProcess.class, temporary.resolve("payloads.db").toString(), ref.get(0), ref.get(1));
    assertThat(java.nio.file.Files.readString(temporary.resolve("child-output.txt")))
        .contains("RESTORED_VERIFIED_PARTS");
  }

  private void runProcess(Class<?> entry, String... args) throws Exception {
    Path output = temporary.resolve("child-output.txt");
    Path classpath = temporary.resolve("classpath.args");
    // Java argument files also work when the Windows command line cannot hold the test classpath.
    java.nio.file.Files.writeString(classpath, "-cp\n\""
        + System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n");
    var arguments = new java.util.ArrayList<String>();
    arguments.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    arguments.add("@" + classpath);
    arguments.add(entry.getName());
    arguments.addAll(List.of(args));
    var command = new ProcessBuilder(arguments)
        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
    try {
      assertThat(command.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(command.exitValue()).isZero();
    } finally {
      if (command.isAlive()) command.destroyForcibly();
    }
  }

  public static final class WriterProcess {
    public static void main(String[] args) throws Exception {
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + args[0]);
      var store = new ModelMessagePayloadStore(source);
      var scope = new ModelMessagePayloadStore.Scope("project", "root", "conversation");
      var ref = store.save(scope, "run", "one", new ModelMessagePayloadStoreTest().message());
      store.publish(scope, ref, ModelMessagePayloadStore.ReferenceKind.RUN, "run");
      java.nio.file.Files.write(Path.of(args[1]), List.of(ref.id(), ref.sha256()));
    }
  }

  /** Runs in a fresh JVM, with no shared codec, message or DataSource instance. */
  public static final class ReaderProcess {
    public static void main(String[] args) {
      var source = new SQLiteDataSource();
      source.setUrl("jdbc:sqlite:" + args[0]);
      var store = new ModelMessagePayloadStore(source);
      var scope = new ModelMessagePayloadStore.Scope("project", "root", "conversation");
      var restored = (AssistantMessage) store.load(scope, new ModelMessagePayloadStore.Ref(args[1], args[2]));
      if (restored.getParts().size() != 3
          || !(restored.getParts().getFirst() instanceof ReasoningPart reasoning)
          || !"opaque fixture bytes".equals(reasoning.payload().data())
          || !"call_fixture".equals(restored.getToolCalls().getFirst().id())) throw new AssertionError("Restore failed");
      System.out.println("RESTORED_VERIFIED_PARTS");
    }
  }

  @Test
  void concurrentWritersCannotExceedTotalQuotaAndGcCannotRemovePublishedMessages() throws Exception {
    int size = new ModelMessageCodec().encode(message()).length;
    var time = new Time();
    var store = store(time, size * 10L, size * 2L);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new java.util.ArrayList<java.util.concurrent.Future<ModelMessagePayloadStore.Ref>>();
      for (int i = 0; i < 8; i++) {
        String key = "message-" + i;
        futures.add(workers.submit(() -> {
          start.await();
          try {
            var ref = store.save(owner, "run", key, message());
            store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.RUN, "run");
            return ref;
          } catch (IllegalStateException capacity) {
            assertThat(capacity).hasMessage("Model message persistence rejected");
            return null;
          }
        }));
      }
      var gc = workers.submit(() -> {
        start.await();
        for (int i = 0; i < 12; i++) assertThat(store.collectExpired()).isZero();
        return null;
      });
      start.countDown();
      var saved = new java.util.ArrayList<ModelMessagePayloadStore.Ref>();
      for (var future : futures) {
        var ref = future.get(15, java.util.concurrent.TimeUnit.SECONDS);
        if (ref != null) saved.add(ref);
      }
      gc.get(15, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(saved).hasSize(2);
      time.expire();
      assertThat(store.collectExpired()).isZero();
      for (var ref : saved) assertThat(store.load(owner, ref)).isInstanceOf(AssistantMessage.class);
      assertThat(store.usage(owner, "run")).isEqualTo(new ModelMessagePayloadStore.Usage(size * 2L, size * 2L, 2, 2));
    }
  }

  @Test
  void aFutureSchemaIsRefusedWithoutChangingItOrUnrelatedTables() throws Exception {
    try (var db = source().getConnection(); var sql = db.createStatement()) {
      sql.execute("CREATE TABLE model_message_schema(version INTEGER NOT NULL)");
      sql.execute("INSERT INTO model_message_schema VALUES(999)");
      sql.execute("CREATE TABLE legacy_conversation(text TEXT)");
      sql.execute("INSERT INTO legacy_conversation VALUES('legacy fixture')");
    }
    assertThatThrownBy(() -> new ModelMessagePayloadStore(source())).hasMessage("Model message persistence rejected");
    try (var db = source().getConnection(); var sql = db.createStatement()) {
      try (var row = sql.executeQuery("SELECT version FROM model_message_schema")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getInt(1)).isEqualTo(999);
      }
      try (var row = sql.executeQuery("SELECT text FROM legacy_conversation")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getString(1)).isEqualTo("legacy fixture");
      }
    }
  }

  @Test
  void corruptedStoredBodyCannotBeRestoredOrPublished() throws Exception {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "one", message());
    try (var db = source().getConnection(); var change = db.prepareStatement(
        "UPDATE model_message_payloads SET payload=? WHERE id=?")) {
      byte[] tampered = new ModelMessageCodec().encode(message());
      tampered[tampered.length - 4] ^= 1;
      change.setBytes(1, tampered);
      change.setString(2, ref.id());
      change.executeUpdate();
    }
    assertThatThrownBy(() -> store.load(owner, ref)).hasMessage("Model message persistence rejected").hasNoCause();
    assertThatThrownBy(() -> store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.RUN, "run"))
        .hasMessage("Model message persistence rejected").hasNoCause();
    assertThat(store.usage(owner, "run").referenceCount()).isZero();
  }

  @Test
  void onlyExpiredUnreferencedPayloadsAreCollectedAndLatePublicationFails() {
    var time = new Time();
    var store = store(time, 100_000, 1_000_000);
    var staged = store.save(owner, "run", "staged", message());
    var published = store.save(owner, "run", "published", message());
    store.publish(owner, published, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
    store.publish(owner, published, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
    assertThat(store.collectExpired()).isZero();
    time.expire();
    assertThatThrownBy(() -> store.publish(owner, staged, ModelMessagePayloadStore.ReferenceKind.RUN, "late"))
        .hasMessage("Model message persistence rejected");
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThat(store.load(owner, published).getText()).isEqualTo("fixture commentary");
    assertThatThrownBy(() -> store.load(owner, staged)).hasMessage("Model message persistence rejected");
    store.release(owner, published, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThatThrownBy(() -> store.publish(owner, published, ModelMessagePayloadStore.ReferenceKind.RUN, "late"))
        .hasMessage("Model message persistence rejected");
  }

  @Test
  void everyDurableReferenceKindProtectsAcrossStoreRestart() {
    var time = new Time();
    var store = store(time, 100_000, 1_000_000);
    var ref = store.save(owner, "run", "message", message());
    for (var kind : ModelMessagePayloadStore.ReferenceKind.values()) store.publish(owner, ref, kind, "owner");
    time.expire();
    var restarted = store(time, 100_000, 1_000_000);
    assertThat(restarted.collectExpired()).isZero();
    for (var kind : ModelMessagePayloadStore.ReferenceKind.values()) {
      assertThat(restarted.load(owner, ref)).isInstanceOf(AssistantMessage.class);
      restarted.release(owner, ref, kind, "owner");
      if (kind != ModelMessagePayloadStore.ReferenceKind.CHECKPOINT) assertThat(restarted.collectExpired()).isZero();
    }
    assertThat(restarted.collectExpired()).isEqualTo(1);
  }

  @Test
  void runCumulativeQuotaSurvivesCollectionAndIdempotentRetryDoesNotDoubleCharge() {
    var time = new Time();
    int size = new ModelMessageCodec().encode(message()).length;
    var store = store(time, size, size * 10L);
    var ref = store.save(owner, "run", "one", message());
    assertThat(store.save(owner, "run", "one", message())).isEqualTo(ref);
    assertThat(store.usage(owner, "run")).isEqualTo(new ModelMessagePayloadStore.Usage(size, size, 1, 0));
    assertThatThrownBy(() -> store.save(owner, "run", "two", message())).hasMessage("Model message persistence rejected");
    time.expire();
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThat(store.usage(owner, "run")).isEqualTo(new ModelMessagePayloadStore.Usage(0, size, 0, 0));
    assertThatThrownBy(() -> store.save(owner, "run", "three", message())).hasMessage("Model message persistence rejected");
    assertThat(store.save(owner, "other-run", "one", message())).isNotNull();
  }

  @Test
  void totalQuotaIncludesOtherOwnersAndRejectedWritesLeaveNoAccountingOrPayload() {
    var time = new Time();
    int size = new ModelMessageCodec().encode(message()).length;
    var store = store(time, size * 10L, size);
    store.save(owner, "run", "one", message());
    var other = new ModelMessagePayloadStore.Scope("other", "root", "conversation");
    assertThatThrownBy(() -> store.save(other, "run", "one", message())).hasMessage("Model message persistence rejected");
    assertThat(store.usage(other, "run")).isEqualTo(new ModelMessagePayloadStore.Usage(size, 0, 1, 0));
    time.expire();
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThat(store.save(other, "run", "one", message())).isNotNull();
    assertThat(store.usage(other, "run").runCumulativeBytes()).isEqualTo(size);
  }

  SQLiteDataSource source() {
    var config = new org.sqlite.SQLiteConfig();
    config.setBusyTimeout(5000);
    var source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + temporary.resolve("payloads.db"));
    return source;
  }

  AssistantMessage message() {
    return AssistantMessage.builder().parts(List.of(
        new ReasoningPart(null, "fixture summary", new OpaquePayload("openai", "reasoning", "opaque fixture bytes"),
            Map.of("id", "rs_fixture")),
        new TextPart("fixture commentary", null, Map.of("channel", "commentary")),
        new ToolCallPart(new AssistantMessage.ToolCall("call_fixture", "function", "fixture", "{}"),
            null, Map.of("id", "fc_fixture"))))
        .properties(Map.of("rei.llm.replay-origin-owner", "owner", "rei.llm.replay-origin-endpoint", "fingerprint"))
        .build();
  }

  @Test
  void aNewStoreInstanceRestoresEveryPartWithoutChangingOpaqueBytes() {
    var codec = new ModelMessageCodec();
    var input = message();
    var first = new ModelMessagePayloadStore(source());
    var ref = first.save(owner, "run", "iteration-1", input);
    var restarted = new ModelMessagePayloadStore(source());
    assertThat(codec.encode(restarted.load(owner, ref))).containsExactly(codec.encode(input));
    assertThat(ref.sha256()).matches("[0-9a-f]{64}");
  }

  @Test
  void retryIsIdempotentButCannotReplaceAnExistingMessage() {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "iteration-1", message());
    assertThat(store.save(owner, "run", "iteration-1", message())).isEqualTo(ref);
    assertThatThrownBy(() -> store.save(owner, "run", "iteration-1", new AssistantMessage("different")))
        .isInstanceOf(IllegalStateException.class).hasMessage("Model message persistence rejected").hasNoCause();
    assertThat(new ModelMessageCodec().encode(store.load(owner, ref)))
        .containsExactly(new ModelMessageCodec().encode(message()));
  }

  @Test
  void missingWrongOwnerAndTamperedHashAreRejected() {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "iteration-1", message());
    var foreign = new ModelMessagePayloadStore.Scope("other", "root", "conversation");
    assertThatThrownBy(() -> store.load(foreign, ref)).hasMessage("Model message persistence rejected");
    assertThatThrownBy(() -> store.load(owner, new ModelMessagePayloadStore.Ref(ref.id(), "0".repeat(64))))
        .hasMessage("Model message persistence rejected");
    assertThatThrownBy(() -> store.load(owner, new ModelMessagePayloadStore.Ref("missing", ref.sha256())))
        .hasMessage("Model message persistence rejected");
  }
}
