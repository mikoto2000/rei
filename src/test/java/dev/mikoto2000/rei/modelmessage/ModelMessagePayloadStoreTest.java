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
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void legacyInputKeepsTextWithoutTurningUnownedProtocolPartsIntoReplayAuthority(boolean opaque) {
    var store = new ModelMessagePayloadStore(source());
    var exchange = new ModelMessagePayloadStore.Exchange(store, owner, "current", "input");
    var assistant =
        opaque
            ? AssistantMessage.builder()
                .parts(
                    List.of(
                        new ReasoningPart(
                            null,
                            null,
                            new OpaquePayload("openai", "reasoning", "unowned fixture"),
                            Map.of("id", "rs_unowned"))))
                .build()
            : AssistantMessage.builder()
                .content("historical display text")
                .properties(
                    Map.of(
                        "source",
                        "WEB",
                        "sourceId",
                        "fixture",
                        "rei.modelMessage.endpoint",
                        "old-endpoint"))
                .build();
    if (opaque) {
      var ref = store.save(owner, "previous", "opaque", assistant);
      store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
      assertThatThrownBy(() ->
              store.restoreReplayGrant(
                  exchange,
                  List.of(
                      new ModelMessagePayloadStore.ReplayReference(
                          ref, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint")),
                  new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "endpoint")))
          .isInstanceOf(IllegalStateException.class);
      return;
    }
    var ref = store.saveForContinuation(owner, "current", "legacy-input", assistant);
    store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.RUN, "current");
    store.protectCheckpoint(
        owner,
        new ModelMessagePayloadStore.History(
            1,
            "current",
            new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "endpoint"),
            List.of(new ModelMessagePayloadStore.History.Entry("legacy/ASSISTANT", ref))),
        "checkpoint");
    var grant =
        store.restoreReplayGrant(
            exchange,
            List.of(
                new ModelMessagePayloadStore.ReplayReference(
                    ref, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint")),
            new ModelMessagePayloadStore.Origin(exchange.ownerKey(), "endpoint"));
    var restored = (AssistantMessage) store.load(owner, ref);
    assertThat(restored.getText()).isEqualTo("historical display text");
    assertThat(restored.getParts()).allMatch(part -> part instanceof TextPart);
    assertThat(restored.getMetadata())
        .doesNotContainKeys(
            "source",
            "sourceId",
            "rei.modelMessage.endpoint",
            "rei.llm.replay-origin-owner",
            "rei.llm.replay-origin-endpoint");
    assertThat(grant.permits(restored, exchange.ownerKey(), "endpoint")).isTrue();
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"ok", "hash", "scope", "missing", "anchor"})
  void summarizedHistoryRetirementIsAtomicScopedAndKeepsEveryOtherAnchor(String scenario) {
    var time = new Time();
    var store = store(time, 100_000, 1_000_000);
    var first = store.save(owner, "run", "one", message());
    var second = store.save(owner, "run", "two", message());
    for (var ref : List.of(first, second)) {
      store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.HISTORY, "turn:run");
      store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.HISTORY, "turn:other");
      store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
      store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.APPROVAL, "approval");
      store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.RUN, "run");
    }
    var scope = scenario.equals("scope") ? new ModelMessagePayloadStore.Scope("foreign", "root", "conversation") : owner;
    var secondPointer = scenario.equals("hash") ? new ModelMessagePayloadStore.Ref(second.id(), "0".repeat(64))
        : scenario.equals("missing") ? new ModelMessagePayloadStore.Ref("missing", second.sha256()) : second;
    if (java.util.Set.of("hash", "scope", "missing").contains(scenario)) {
      assertThatThrownBy(() -> store.releaseHistory(scope, "turn:run", List.of(first, secondPointer)))
          .hasMessage("Model message persistence rejected").hasNoCause();
      assertThat(store.usage(owner, "run").referenceCount()).isEqualTo(10);
      assertThat(store.load(owner, first, ModelMessagePayloadStore.ReferenceKind.HISTORY, "turn:run")).isNotNull();
      return;
    }
    assertThat(store.releaseHistory(scope, scenario.equals("anchor") ? "unknown" : "turn:run", List.of(first, second)))
        .isEqualTo(scenario.equals("anchor") ? 0 : 2);
    if (scenario.equals("anchor")) return;
    assertThat(store.usage(owner, "run").referenceCount()).isEqualTo(8);
    for (var ref : List.of(first, second)) {
      assertThat(store.load(owner, ref, ModelMessagePayloadStore.ReferenceKind.HISTORY, "turn:other")).isNotNull();
      store.release(owner, ref, ModelMessagePayloadStore.ReferenceKind.HISTORY, "turn:other");
      store.release(owner, ref, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
      store.release(owner, ref, ModelMessagePayloadStore.ReferenceKind.APPROVAL, "approval");
      store.release(owner, ref, ModelMessagePayloadStore.ReferenceKind.RUN, "run");
    }
    time.expire();
    assertThat(store.collectExpired()).isEqualTo(2);
    var restarted = store(time, 100_000, 1_000_000);
    assertThat(restarted.releaseHistory(scope, "turn:run", List.of(first, second))).isZero();
    assertThat(restarted.usage(owner, "run").referenceCount()).isZero();
  }

  @Test
  void replayGrantUnionRejectsDifferentOwnersAndEndpoints() {
    var store = new ModelMessagePayloadStore(source());
    var first = new ModelMessagePayloadStore.Exchange(store, owner, "first", "request");
    var second = new ModelMessagePayloadStore.Exchange(store, owner, "second", "request");
    var original =
        store.restoreReplayGrant(
            first, List.of(), new ModelMessagePayloadStore.Origin(first.ownerKey(), "endpoint"));
    var foreign =
        store.restoreReplayGrant(
            second, List.of(), new ModelMessagePayloadStore.Origin(second.ownerKey(), "endpoint"));
    var changedEndpoint =
        store.restoreReplayGrant(
            first,
            List.of(),
            new ModelMessagePayloadStore.Origin(first.ownerKey(), "changed-endpoint"));
    assertThatThrownBy(() -> original.merge(foreign)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> original.merge(changedEndpoint))
        .isInstanceOf(IllegalStateException.class);
    assertThat(original.merge(original).toString()).isEqualTo("HistoryReplayGrant[messageCount=0]");
  }

  @Test
  void connectionRuntimeFailuresDoNotExposeConfigurationOrCauses() {
    var source =
        new org.springframework.jdbc.datasource.AbstractDataSource() {
          @Override
          public java.sql.Connection getConnection() {
            throw new IllegalStateException(
                "PRIVATE_DATABASE_CONFIG_FIXTURE", new RuntimeException("PRIVATE_CAUSE_FIXTURE"));
          }

          @Override
          public java.sql.Connection getConnection(String username, String password) {
            return getConnection();
          }
        };
    assertThatThrownBy(() -> new ModelMessagePayloadStore(source))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Model message persistence rejected")
        .hasNoCause();
  }

  @TempDir Path temporary;
  private final ModelMessagePayloadStore.Scope owner =
      new ModelMessagePayloadStore.Scope("project", "root", "conversation");

  @Test
  void springLeavesPayloadSchemaUninitializedUntilUsedAndInjectsExistingHistoryAndMaintenance()
      throws Exception {
    try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(temporary)) {
      migration.prepare();
    }
    var turns = dev.mikoto2000.rei.conversation.ConversationTurnStore.inMemory();
    try (var context =
        new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
      context.registerBean(
          "memoryConsolidationDataSource", javax.sql.DataSource.class, this::source);
      context.register(ModelMessagePayloadStore.class);
      context.registerBean(
          dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.class,
          () ->
              new dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor(
                  turns,
                  dev.mikoto2000.rei.chatmemory.WindowedConversationMemory.builder().build(),
                  new dev.mikoto2000.rei.core.contextbudget.ConversationSummaryRepository(
                      temporary)));
      context.registerBean(
          dev.mikoto2000.rei.storage.StorageMaintenance.class,
          () ->
              new dev.mikoto2000.rei.storage.StorageMaintenance(
                  new dev.mikoto2000.rei.storage.StorageObjectRegistry(temporary)));
      context.refresh();
      try (var db = source().getConnection();
          var query = db.createStatement();
          var row =
              query.executeQuery(
                  "SELECT COUNT(*) FROM sqlite_master WHERE name='model_message_schema'")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getInt(1)).isZero();
      }
      var store = context.getBean(ModelMessagePayloadStore.class);
      var current =
          new dev.mikoto2000.rei.core.chat.AgentRunContext("current", "conversation", temporary);
      var old = new dev.mikoto2000.rei.core.chat.AgentRunContext("old", "conversation", temporary);
      var scope = ModelMessagePayloadStore.Scope.forOwner(current);
      var input =
          AssistantMessage.builder()
              .parts(message().getParts().subList(0, 2))
              .properties(message().getMetadata())
              .build();
      var ref = store.save(scope, "old", "one", input);
      var metadata = store.historyReference(scope, ref, "turn:old");
      turns.start(old, "old request");
      turns.recordMetadata(old, metadata);
      turns.finish(
          old,
          dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.COMPLETED,
          "display fixture");
      var request =
          org.springframework.ai.chat.client.ChatClientRequest.builder()
              .prompt(
                  new org.springframework.ai.chat.prompt.Prompt(
                      new org.springframework.ai.chat.messages.UserMessage("current request")))
              .context(
                  Map.of(
                      org.springframework.ai.chat.client.advisor.api.MemoryAdvisor.CONVERSATION_ID,
                      "conversation",
                      dev.mikoto2000.rei.core.chat.AgentRunContext.class.getName(),
                      current))
              .build();
      var messages =
          context
              .getBean(dev.mikoto2000.rei.core.contextbudget.ContextHistoryAdvisor.class)
              .before(request, null)
              .prompt()
              .getInstructions();
      var restored =
          (AssistantMessage)
              messages.stream()
                  .filter(m -> m instanceof AssistantMessage)
                  .findFirst()
                  .orElseThrow();
      assertThat(restored.getParts()).containsExactlyElementsOf(input.getParts());
      store.save(scope, "old", "orphan", input);
      try (var db = source().getConnection();
          var query = db.createStatement()) {
        query.executeUpdate("UPDATE model_message_payloads SET staged_until=0");
      }
      assertThat(
              context
                  .getBean(dev.mikoto2000.rei.storage.StorageMaintenance.class)
                  .collectModelMessageOrphans())
          .isEqualTo(1);
      assertThat(((AssistantMessage) store.load(scope, ref)).getParts())
          .containsExactlyElementsOf(input.getParts());
    }
  }

  static class Time extends java.time.Clock {
    java.time.Instant now = java.time.Instant.parse("2026-11-24T00:00:00Z");

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public java.time.Instant instant() {
      return now;
    }

    void expire() {
      now = now.plusSeconds(61);
    }
  }

  ModelMessagePayloadStore store(Time time, long runBytes, long totalBytes) {
    return new ModelMessagePayloadStore(
        source(),
        time,
        new ModelMessagePayloadStore.Limits(
            runBytes, totalBytes, java.time.Duration.ofSeconds(60)));
  }

  @Test
  void aSeparateJvmReadsTheSameDurablePartsAfterTheWriterHasExited() throws Exception {
    Path references = temporary.resolve("reference.txt");
    runProcess(
        WriterProcess.class, temporary.resolve("payloads.db").toString(), references.toString());
    var ref = java.nio.file.Files.readAllLines(references);
    runProcess(
        ReaderProcess.class, temporary.resolve("payloads.db").toString(), ref.get(0), ref.get(1));
    assertThat(java.nio.file.Files.readString(temporary.resolve("child-output.txt")))
        .contains("RESTORED_VERIFIED_PARTS");
  }

  private void runProcess(Class<?> entry, String... args) throws Exception {
    Path output = temporary.resolve("child-output.txt");
    Path classpath = temporary.resolve("classpath.args");
    // Java argument files also work when the Windows command line cannot hold the test classpath.
    java.nio.file.Files.writeString(
        classpath,
        "-cp\n\""
            + System.getProperty("java.class.path").replace("\\", "\\\\").replace("\"", "\\\"")
            + "\"\n");
    var arguments = new java.util.ArrayList<String>();
    arguments.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    arguments.add("@" + classpath);
    arguments.add(entry.getName());
    arguments.addAll(List.of(args));
    var command =
        new ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
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
      var restored =
          (AssistantMessage) store.load(scope, new ModelMessagePayloadStore.Ref(args[1], args[2]));
      if (restored.getParts().size() != 3
          || !(restored.getParts().getFirst() instanceof ReasoningPart reasoning)
          || !"opaque fixture bytes".equals(reasoning.payload().data())
          || !"call_fixture".equals(restored.getToolCalls().getFirst().id()))
        throw new AssertionError("Restore failed");
      System.out.println("RESTORED_VERIFIED_PARTS");
    }
  }

  @Test
  void concurrentWritersCannotExceedTotalQuotaAndGcCannotRemovePublishedMessages()
      throws Exception {
    int size = new ModelMessageCodec().encode(message()).length;
    var time = new Time();
    var store = store(time, size * 10L, size * 2L);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var futures =
          new java.util.ArrayList<java.util.concurrent.Future<ModelMessagePayloadStore.Ref>>();
      for (int i = 0; i < 8; i++) {
        String key = "message-" + i;
        futures.add(
            workers.submit(() -> {
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
      var gc =
          workers.submit(() -> {
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
      assertThat(store.usage(owner, "run"))
          .isEqualTo(new ModelMessagePayloadStore.Usage(size * 2L, size * 2L, 2, 2));
    }
  }

  @Test
  void aFutureSchemaIsRefusedWithoutChangingItOrUnrelatedTables() throws Exception {
    try (var db = source().getConnection();
        var sql = db.createStatement()) {
      sql.execute("CREATE TABLE model_message_schema(version INTEGER NOT NULL)");
      sql.execute("INSERT INTO model_message_schema VALUES(999)");
      sql.execute("CREATE TABLE legacy_conversation(text TEXT)");
      sql.execute("INSERT INTO legacy_conversation VALUES('legacy fixture')");
    }
    assertThatThrownBy(() -> new ModelMessagePayloadStore(source()))
        .hasMessage("Model message persistence rejected");
    try (var db = source().getConnection();
        var sql = db.createStatement()) {
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
    try (var db = source().getConnection();
        var change =
            db.prepareStatement("UPDATE model_message_payloads SET payload=? WHERE id=?")) {
      byte[] tampered = new ModelMessageCodec().encode(message());
      tampered[tampered.length - 4] ^= 1;
      change.setBytes(1, tampered);
      change.setString(2, ref.id());
      change.executeUpdate();
    }
    assertThatThrownBy(() -> store.load(owner, ref))
        .hasMessage("Model message persistence rejected")
        .hasNoCause();
    assertThatThrownBy(
            () -> store.publish(owner, ref, ModelMessagePayloadStore.ReferenceKind.RUN, "run"))
        .hasMessage("Model message persistence rejected")
        .hasNoCause();
    assertThat(store.usage(owner, "run").referenceCount()).isZero();
  }

  @Test
  void aFailedUpgradeRollsBackEverySchemaChangeAndTheOriginalPayloadRemainsReadable()
      throws Exception {
    byte[] bytes = new ModelMessageCodec().encode(message());
    String hash =
        java.util.HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    try (var db = source().getConnection();
        var sql = db.createStatement()) {
      sql.execute("CREATE TABLE model_message_schema(version INTEGER NOT NULL)");
      sql.execute("INSERT INTO model_message_schema VALUES(1)");
      sql.execute(
          "CREATE TABLE model_message_payloads(id TEXT PRIMARY KEY,project TEXT NOT NULL,root_path TEXT NOT NULL,conversation TEXT NOT NULL,run TEXT NOT NULL,message_key TEXT NOT NULL,sha256 TEXT NOT NULL,payload BLOB NOT NULL,size INTEGER NOT NULL,UNIQUE(project,root_path,conversation,run,message_key))");
      try (var insert =
          db.prepareStatement(
              "INSERT INTO model_message_payloads VALUES('legacy','project','root','conversation','run','one',?,?,?)")) {
        insert.setString(1, hash);
        insert.setBytes(2, bytes);
        insert.setInt(3, bytes.length);
        insert.executeUpdate();
      }
    }
    assertThatThrownBy(() ->
            new ModelMessagePayloadStore(failingSource("CREATE TABLE model_message_receipts")))
        .hasMessage("Model message persistence rejected")
        .hasNoCause();
    try (var db = source().getConnection();
        var sql = db.createStatement()) {
      try (var row = sql.executeQuery("SELECT version FROM model_message_schema")) {
        assertThat(row.next()).isTrue();
        assertThat(row.getInt(1)).isEqualTo(1);
      }
      try (var row =
          sql.executeQuery(
              "SELECT name FROM pragma_table_info('model_message_payloads') WHERE name='staged_until'")) {
        assertThat(row.next()).isFalse();
      }
      try (var row =
          sql.executeQuery(
              "SELECT name FROM sqlite_master WHERE name='model_message_references'")) {
        assertThat(row.next()).isFalse();
      }
    }
    var time = new Time();
    var upgraded = store(time, 100_000, 1_000_000);
    var ref = new ModelMessagePayloadStore.Ref("legacy", hash);
    assertThat(new ModelMessageCodec().encode(upgraded.load(owner, ref))).containsExactly(bytes);
    assertThat(upgraded.save(owner, "run", "one", message())).isEqualTo(ref);
    time.expire();
    assertThat(upgraded.collectExpired()).isZero();
    assertThat(upgraded.usage(owner, "run").runCumulativeBytes()).isEqualTo(bytes.length);
  }

  @Test
  void failureAfterBodyAndReceiptInsertionRollsBackBothAndDoesNotConsumeQuota() {
    var healthy = new ModelMessagePayloadStore(source());
    var failed = new ModelMessagePayloadStore(failingSource("INSERT INTO model_message_run_usage"));
    assertThatThrownBy(() -> failed.save(owner, "run", "one", message()))
        .hasMessage("Model message persistence rejected")
        .hasNoCause();
    assertThat(healthy.usage(owner, "run"))
        .isEqualTo(new ModelMessagePayloadStore.Usage(0, 0, 0, 0));
    var ref = healthy.save(owner, "run", "one", message());
    assertThat(healthy.load(owner, ref)).isInstanceOf(AssistantMessage.class);
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
  void theExistingToolLoopUsesRealDurableAssistantAndResultBoundaries(
      boolean resultCapacityFailure) {
    int assistantSize = new ModelMessageCodec().encode(message()).length;
    var store = store(new Time(), resultCapacityFailure ? assistantSize : 100_000, 1_000_000);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    var callback =
        new org.springframework.ai.tool.ToolCallback() {
          @Override
          public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                .name("fixture")
                .description("fixture")
                .inputSchema("{}")
                .build();
          }

          @Override
          public String call(String arguments) {
            assertThat(store.usage(owner, "run").referenceCount()).isEqualTo(1);
            var saved = store.save(owner, "run", "iteration/ASSISTANT", message());
            assertThat(((AssistantMessage) store.load(owner, saved)).getParts())
                .containsExactlyElementsOf(message().getParts());
            calls.incrementAndGet();
            return "fixture result";
          }
        };
    var prompt =
        new org.springframework.ai.chat.prompt.Prompt(
            "fixture request",
            org.springframework.ai.openai.OpenAiChatOptions.builder()
                .toolCallbacks(callback)
                .toolContext(
                    Map.of(
                        ModelMessagePayloadStore.EXCHANGE_CONTEXT_KEY,
                        new ModelMessagePayloadStore.Exchange(store, owner, "run", "iteration")))
                .build());
    var response =
        new org.springframework.ai.chat.model.ChatResponse(
            List.of(new org.springframework.ai.chat.model.Generation(message())));
    var loop = new dev.mikoto2000.rei.core.chat.ToolLoopSupport();
    if (resultCapacityFailure) {
      assertThatThrownBy(() -> loop.execute(prompt, response))
          .hasMessage("Model message persistence rejected");
      assertThat(calls).hasValue(1);
      assertThat(store.usage(owner, "run").payloadCount()).isEqualTo(1);
    } else {
      var executed = loop.execute(prompt, response);
      var delivered = executed.conversationHistory().getLast();
      var saved = store.save(owner, "run", "iteration/TOOL", delivered);
      assertThat(new ModelMessageCodec().encode(store.load(owner, saved)))
          .containsExactly(new ModelMessageCodec().encode(delivered));
      assertThat(calls).hasValue(1);
      assertThat(store.usage(owner, "run").referenceCount()).isEqualTo(2);
    }
  }

  private javax.sql.DataSource failingSource(String prefix) {
    var actual = source();
    return new org.springframework.jdbc.datasource.AbstractDataSource() {
      @Override
      public java.sql.Connection getConnection() throws java.sql.SQLException {
        var connection = actual.getConnection();
        return (java.sql.Connection)
            java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {java.sql.Connection.class},
                (proxy, method, args) -> {
                  Object result = invoke(method, connection, args);
                  if (method.getName().equals("createStatement")) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {java.sql.Statement.class},
                        (statement, operation, parameters) -> {
                          if (operation.getName().equals("execute")
                              && ((String) parameters[0]).startsWith(prefix))
                            throw new java.sql.SQLException("PRIVATE_FAILURE_FIXTURE");
                          return invoke(operation, result, parameters);
                        });
                  }
                  if (method.getName().equals("prepareStatement")
                      && ((String) args[0]).startsWith(prefix)) {
                    return java.lang.reflect.Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {java.sql.PreparedStatement.class},
                        (statement, operation, parameters) -> {
                          if (operation.getName().equals("executeUpdate"))
                            throw new java.sql.SQLException("PRIVATE_FAILURE_FIXTURE");
                          return invoke(operation, result, parameters);
                        });
                  }
                  return result;
                });
      }

      @Override
      public java.sql.Connection getConnection(String username, String password)
          throws java.sql.SQLException {
        return getConnection();
      }
    };
  }

  private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args)
      throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (java.lang.reflect.InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  @Test
  void onlyExpiredUnreferencedPayloadsAreCollectedAndLatePublicationFails() {
    var time = new Time();
    var store = store(time, 100_000, 1_000_000);
    var staged = store.save(owner, "run", "staged", message());
    var published = store.save(owner, "run", "published", message());
    store.publish(
        owner, published, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
    store.publish(
        owner, published, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
    assertThat(store.collectExpired()).isZero();
    time.expire();
    assertThatThrownBy(
            () -> store.publish(owner, staged, ModelMessagePayloadStore.ReferenceKind.RUN, "late"))
        .hasMessage("Model message persistence rejected");
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThat(store.load(owner, published).getText()).isEqualTo("fixture commentary");
    assertThatThrownBy(() -> store.load(owner, staged))
        .hasMessage("Model message persistence rejected");
    store.release(
        owner, published, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThatThrownBy(() ->
            store.publish(owner, published, ModelMessagePayloadStore.ReferenceKind.RUN, "late"))
        .hasMessage("Model message persistence rejected");
  }

  @Test
  void existingStorageMaintenanceCommandCollectsOnlyExpiredUnreferencedBodies() throws Exception {
    var time = new Time();
    var store = store(time, 100_000, 1_000_000);
    store.save(owner, "run", "orphan", message());
    var protectedRef = store.save(owner, "run", "protected", message());
    store.publish(
        owner, protectedRef, ModelMessagePayloadStore.ReferenceKind.CHECKPOINT, "checkpoint");
    time.expire();
    try (var migration = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(temporary)) {
      migration.prepare();
    }
    var maintenance =
        new dev.mikoto2000.rei.storage.StorageMaintenance(
            new dev.mikoto2000.rei.storage.StorageObjectRegistry(temporary), store::collectExpired);
    var command =
        new picocli.CommandLine(
            new dev.mikoto2000.rei.core.command.StorageCommand(null, null, maintenance, null));
    var output = new java.io.StringWriter();
    command.setOut(new java.io.PrintWriter(output));
    command.setErr(new java.io.PrintWriter(output));
    assertThat(command.execute("maintenance", "model-messages-gc")).isZero();
    assertThat(output.toString()).contains("collected_model_messages=1");
    assertThat(store.load(owner, protectedRef)).isInstanceOf(AssistantMessage.class);
    assertThat(store.usage(owner, "run").payloadCount()).isEqualTo(1);
  }

  @Test
  void everyDurableReferenceKindProtectsAcrossStoreRestart() {
    var time = new Time();
    var store = store(time, 100_000, 1_000_000);
    var ref = store.save(owner, "run", "message", message());
    for (var kind : ModelMessagePayloadStore.ReferenceKind.values())
      store.publish(owner, ref, kind, "owner");
    time.expire();
    var restarted = store(time, 100_000, 1_000_000);
    assertThat(restarted.collectExpired()).isZero();
    for (var kind : ModelMessagePayloadStore.ReferenceKind.values()) {
      assertThat(restarted.load(owner, ref)).isInstanceOf(AssistantMessage.class);
      restarted.release(owner, ref, kind, "owner");
      if (kind != ModelMessagePayloadStore.ReferenceKind.CHECKPOINT)
        assertThat(restarted.collectExpired()).isZero();
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
    assertThat(store.usage(owner, "run"))
        .isEqualTo(new ModelMessagePayloadStore.Usage(size, size, 1, 0));
    assertThatThrownBy(() -> store.save(owner, "run", "two", message()))
        .hasMessage("Model message persistence rejected");
    time.expire();
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThat(store.usage(owner, "run"))
        .isEqualTo(new ModelMessagePayloadStore.Usage(0, size, 0, 0));
    assertThatThrownBy(() -> store.save(owner, "run", "three", message()))
        .hasMessage("Model message persistence rejected");
    assertThat(store.save(owner, "other-run", "one", message())).isNotNull();
  }

  @Test
  void totalQuotaIncludesOtherOwnersAndRejectedWritesLeaveNoAccountingOrPayload() {
    var time = new Time();
    int size = new ModelMessageCodec().encode(message()).length;
    var store = store(time, size * 10L, size);
    store.save(owner, "run", "one", message());
    var other = new ModelMessagePayloadStore.Scope("other", "root", "conversation");
    assertThatThrownBy(() -> store.save(other, "run", "one", message()))
        .hasMessage("Model message persistence rejected");
    assertThat(store.usage(other, "run"))
        .isEqualTo(new ModelMessagePayloadStore.Usage(size, 0, 1, 0));
    time.expire();
    assertThat(store.collectExpired()).isEqualTo(1);
    assertThat(store.save(other, "run", "one", message())).isNotNull();
    assertThat(store.usage(other, "run").runCumulativeBytes()).isEqualTo(size);
  }

  @Test
  void collectionDoesNotAllowAnIdempotencyKeyToBeReusedEvenByAnotherStoreInstance() {
    var time = new Time();
    var store = store(time, 100_000, 1_000_000);
    store.save(owner, "run", "one", message());
    time.expire();
    assertThat(store.collectExpired()).isEqualTo(1);
    var restarted = store(time, 100_000, 1_000_000);
    assertThatThrownBy(() -> restarted.save(owner, "run", "one", message()))
        .hasMessage("Model message persistence rejected");
    assertThatThrownBy(() -> restarted.save(owner, "run", "one", new AssistantMessage("different")))
        .hasMessage("Model message persistence rejected");
    assertThat(restarted.usage(owner, "run").payloadCount()).isZero();
  }

  SQLiteDataSource source() {
    var config = new org.sqlite.SQLiteConfig();
    config.setBusyTimeout(5000);
    var source = new SQLiteDataSource(config);
    source.setUrl("jdbc:sqlite:" + temporary.resolve("payloads.db"));
    return source;
  }

  AssistantMessage message() {
    return AssistantMessage.builder()
        .parts(
            List.of(
                new ReasoningPart(
                    null,
                    "fixture summary",
                    new OpaquePayload("openai", "reasoning", "opaque fixture bytes"),
                    Map.of("id", "rs_fixture")),
                new TextPart("fixture commentary", null, Map.of("channel", "commentary")),
                new ToolCallPart(
                    new AssistantMessage.ToolCall("call_fixture", "function", "fixture", "{}"),
                    null,
                    Map.of("id", "fc_fixture"))))
        .properties(
            Map.of(
                "rei.llm.replay-origin-owner",
                "owner",
                "rei.llm.replay-origin-endpoint",
                "fingerprint"))
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
  void historyContainsOnlyAPublishedPointerAndMissingOrPartialPointersCannotBecomeTextFallback() {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "one", message());
    var metadata = store.historyReference(owner, ref, "turn:run:one");
    assertThat(metadata).hasSize(3).doesNotContainValue("opaque fixture bytes");
    var restarted = new ModelMessagePayloadStore(source());
    assertThat(
            ((AssistantMessage) restarted.restoreHistory(owner, metadata).orElseThrow()).getParts())
        .containsExactlyElementsOf(message().getParts());
    assertThat(restarted.restoreHistory(null, Map.of("source", "legacy"))).isEmpty();
    var partial = new java.util.HashMap<>(metadata);
    partial.remove(ModelMessagePayloadStore.HISTORY_HASH);
    assertThatThrownBy(() -> restarted.restoreHistory(owner, partial))
        .hasMessage("Model message persistence rejected");
    var unpublished = new java.util.HashMap<>(metadata);
    unpublished.put(ModelMessagePayloadStore.HISTORY_OWNER, "different-owner");
    assertThatThrownBy(() -> restarted.restoreHistory(owner, unpublished))
        .hasMessage("Model message persistence rejected");
    store.release(owner, ref, ModelMessagePayloadStore.ReferenceKind.HISTORY, "turn:run:one");
    assertThatThrownBy(() -> restarted.restoreHistory(owner, metadata))
        .hasMessage("Model message persistence rejected");
  }

  @Test
  void retryIsIdempotentButCannotReplaceAnExistingMessage() {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "iteration-1", message());
    assertThat(store.save(owner, "run", "iteration-1", message())).isEqualTo(ref);
    assertThatThrownBy(
            () -> store.save(owner, "run", "iteration-1", new AssistantMessage("different")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Model message persistence rejected")
        .hasNoCause();
    assertThat(new ModelMessageCodec().encode(store.load(owner, ref)))
        .containsExactly(new ModelMessageCodec().encode(message()));
  }

  @Test
  void missingWrongOwnerAndTamperedHashAreRejected() {
    var store = new ModelMessagePayloadStore(source());
    var ref = store.save(owner, "run", "iteration-1", message());
    var foreign = new ModelMessagePayloadStore.Scope("other", "root", "conversation");
    assertThatThrownBy(() -> store.load(foreign, ref))
        .hasMessage("Model message persistence rejected");
    assertThatThrownBy(
            () -> store.load(owner, new ModelMessagePayloadStore.Ref(ref.id(), "0".repeat(64))))
        .hasMessage("Model message persistence rejected");
    assertThatThrownBy(
            () -> store.load(owner, new ModelMessagePayloadStore.Ref("missing", ref.sha256())))
        .hasMessage("Model message persistence rejected");
  }
}
