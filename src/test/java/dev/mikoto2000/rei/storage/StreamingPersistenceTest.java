package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventBus;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.EventStorageConfiguration;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.MessageDeltaPayload;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.event.ProfileEventLogSubscriber;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.event.ProjectAgentEventSubscriber;
import dev.mikoto2000.rei.event.StreamingEventPersistence;
import dev.mikoto2000.rei.event.ThinkingDeltaPayload;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** 実際のSpring配線・SQLite・JSONLで共同確定の耐久性と失敗境界を検証する。 */
@Tag("integration")
class StreamingPersistenceTest {
  @TempDir Path root;
  static final String PROJECT = "00000000-0000-0000-0000-000000000001";

  static AnnotationConfigApplicationContext context(Path root, InMemoryAgentEventBus bus) {
    var context = new AnnotationConfigApplicationContext();
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of("rei.data-dir", root.toString())));
    context.registerBean(InMemoryAgentEventBus.class, () -> bus);
    context.register(
        StorageMigrationConfiguration.class,
        EventStorageConfiguration.class,
        ProfileEventLogStore.class,
        ProfileEventLogSubscriber.class,
        ProjectAgentEventSubscriber.class,
        StreamingEventPersistence.class);
    context.refresh();
    return context;
  }

  static AgentEvent event(String id, String project, boolean thinking) {
    return new AgentEvent(
        id,
        0,
        Instant.EPOCH,
        thinking ? AgentEventType.THINKING_DELTA : AgentEventType.MESSAGE_DELTA,
        1,
        "session",
        null,
        "run",
        null,
        null,
        thinking
            ? new ThinkingDeltaPayload("thought", "private-stream-text")
            : new MessageDeltaPayload("answer", "private-stream-text"),
        project);
  }

  @Test
  void oneFullCommitPerChunkWithBothStoresDurableBeforeEvenAnEarlyConsumer() throws Exception {
    var bus = new InMemoryAgentEventBus();
    var displayed = new ArrayList<AgentEvent>();
    var stores =
        new java.util.concurrent.atomic.AtomicReference<AnnotationConfigApplicationContext>();
    bus.subscribe(
        event -> {
          var context = stores.get();
          var store = context.getBean(ProjectAgentEventStore.class);
          assertThat(store.findEvent(event.projectId(), event.id())).isPresent();
          var entries = context.getBean(ManagedActivityLog.class).read(event.projectId());
          assertThat(entries)
              .anySatisfy(
                  entry -> {
                    assertThat(entry.id()).isEqualTo(event.id());
                    assertThat(entry.sequence()).isEqualTo(event.sequence());
                  });
          displayed.add(event);
        });
    Class.forName("org.sqlite.JDBC");
    var original = DriverManager.getDriver("jdbc:sqlite:");
    var probe = new AnswerGenerationIoBenchmarkTest.Probe(original);
    DriverManager.deregisterDriver(original);
    DriverManager.registerDriver(probe);
    try {
      try (var context = context(root, bus)) {
        stores.set(context);
        var io = new StorageIoObservation();
        probe.active.set(true);
        try (var scope = io.observe()) {
          for (int i = 0; i < 20; i++) {
            var event = event("chunk-" + i, PROJECT, i % 2 == 0);
            if (i == 10) bus.publishBoundary(event);
            else bus.publish(event);
          }
        } finally {
          probe.active.set(false);
        }
        assertThat(probe.commits.get()).isEqualTo(20);
        assertThat(probe.sequenceStatements.get()).isEqualTo(20);
        assertThat(probe.transactions.get()).isEqualTo(20);
        assertThat(io.forces.sum()).isEqualTo(21);
        assertThat(displayed).hasSize(20);
        assertThat(context.getBean(ManagedActivityLog.class).read(PROJECT).toString())
            .doesNotContain("private-stream-text");
        new StorageDatabase(root)
            .transaction(
                db -> {
                  try (var sql = db.createStatement();
                      var rows = sql.executeQuery("PRAGMA synchronous")) {
                    assertThat(rows.getInt(1)).isEqualTo(2);
                  }
                  return null;
                });
      }
    } finally {
      DriverManager.deregisterDriver(probe);
      DriverManager.registerDriver(original);
    }
    try (var restarted = context(root, new InMemoryAgentEventBus())) {
      assertThat(restarted.getBean(ProjectAgentEventStore.class).readAfter(PROJECT, 0, 100))
          .extracting(AgentEvent::id)
          .containsExactlyElementsOf(displayed.stream().map(AgentEvent::id).toList());
      assertThat(restarted.getBean(ManagedActivityLog.class).read(PROJECT)).hasSize(20);
    }
  }

  @Test
  void ordinaryAndReentrantBoundaryEventsUseOneCommitBeforeDelivery() throws Exception {
    var bus = new InMemoryAgentEventBus();
    var displayed = new ArrayList<String>();
    Class.forName("org.sqlite.JDBC");
    var original = DriverManager.getDriver("jdbc:sqlite:");
    var probe = new AnswerGenerationIoBenchmarkTest.Probe(original);
    DriverManager.deregisterDriver(original);
    DriverManager.registerDriver(probe);
    try (var context = context(root, bus)) {
      var store = context.getBean(ProjectAgentEventStore.class);
      var activity = context.getBean(ManagedActivityLog.class);
      bus.subscribe(
          e -> {
            assertThat(store.findEvent(PROJECT, e.id())).isPresent();
            assertThat(activity.read(PROJECT)).anyMatch(row -> row.id().equals(e.id()));
            displayed.add(e.id());
            if (e.id().equals("ordinary")) {
              bus.publishBoundary(checkpoint("boundary"));
              assertThat(store.findEvent(PROJECT, "boundary")).isPresent();
              assertThat(activity.read(PROJECT)).anyMatch(row -> row.id().equals("boundary"));
            }
          });
      probe.active.set(true);
      try {
        bus.publish(checkpoint("ordinary"));
      } finally {
        probe.active.set(false);
      }
      assertThat(probe.commits.get()).isEqualTo(2);
      assertThat(probe.transactions.get()).isEqualTo(2);
      assertThat(displayed).containsExactly("ordinary", "boundary");
      assertThat(activity.read(PROJECT)).extracting(row -> row.sequence()).containsExactly(1L, 2L);
    } finally {
      DriverManager.deregisterDriver(probe);
      DriverManager.registerDriver(original);
    }
  }

  private static AgentEvent checkpoint(String id) {
    return new AgentEvent(
        id,
        0,
        Instant.EPOCH,
        AgentEventType.CHECKPOINT_REVISION_SAVED,
        1,
        "session",
        null,
        "run",
        null,
        null,
        new dev.mikoto2000.rei.event.CheckpointLifecyclePayload("task", 2, null, null, "saved"),
        PROJECT);
  }

  @Test
  void failedNonDeltaBoundaryDoesNotDeliverOrReplayOrCommitEvent() {
    var bus = new InMemoryAgentEventBus();
    var displayed = new ArrayList<AgentEvent>();
    bus.subscribe(displayed::add);
    try (var context = context(root, bus)) {
      new StorageDatabase(root)
          .transaction(
              db -> {
                try (var sql = db.createStatement()) {
                  sql.execute(
                      "CREATE TRIGGER fail_activity BEFORE UPDATE ON activity_segments BEGIN SELECT RAISE(ABORT,'failure'); END");
                }
                return null;
              });
      assertThatThrownBy(() -> bus.publishBoundary(checkpoint("failed")))
          .isInstanceOf(IllegalStateException.class);
      assertThat(context.getBean(ProjectAgentEventStore.class).findEvent(PROJECT, "failed"))
          .isEmpty();
      assertThat(displayed).isEmpty();
      var replay = bus.subscribe("run", 0, ignored -> {});
      try {
        assertThat(replay.replay()).isEmpty();
      } finally {
        replay.subscription().unsubscribe();
      }
    }
  }

  @Test
  void sequenceOverflowDoesNotPersistOrDeliverAnotherChunk() {
    var bus = new InMemoryAgentEventBus();
    var displayed = new ArrayList<AgentEvent>();
    bus.subscribe(displayed::add);
    try (var context = context(root, bus)) {
      bus.publish(event("good", PROJECT, false));
      new StorageDatabase(root)
          .transaction(
              db -> {
                try (var statement =
                    db.prepareStatement(
                        "UPDATE event_sequences SET sequence=? WHERE project_id=?")) {
                  statement.setLong(1, Long.MAX_VALUE);
                  statement.setString(2, PROJECT);
                  statement.executeUpdate();
                }
                return null;
              });

      assertThatThrownBy(() -> bus.publish(event("overflow", PROJECT, true)))
          .isInstanceOf(ArithmeticException.class);
      assertThat(displayed).extracting(AgentEvent::id).containsExactly("good");
      var store = context.getBean(ProjectAgentEventStore.class);
      assertThat(store.lastSequence(PROJECT)).isEqualTo(Long.MAX_VALUE);
      assertThat(store.findEvent(PROJECT, "overflow")).isEmpty();
      assertThat(context.getBean(ManagedActivityLog.class).read(PROJECT)).hasSize(1);
    }
  }

  @Test
  void concurrentProjectsKeepGaplessIndependentSequences() throws Exception {
    try (var context = context(root, new InMemoryAgentEventBus());
        var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
      var store = context.getBean(ProjectAgentEventStore.class);
      String other = UUID.randomUUID().toString();
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int worker = 0; worker < 4; worker++) {
        int owner = worker;
        String project = worker % 2 == 0 ? PROJECT : other;
        futures.add(
            executor.submit(
                () -> {
                  for (int index = 0; index < 20; index++)
                    store.append(event(owner + "-" + index, project, false));
                }));
      }
      for (var future : futures) future.get(20, java.util.concurrent.TimeUnit.SECONDS);
      for (String project : java.util.List.of(PROJECT, other)) {
        assertThat(store.lastSequence(project)).isEqualTo(40);
        assertThat(store.readAfter(project, 0, 100))
            .extracting(AgentEvent::sequence)
            .containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, 40).boxed().toList());
      }
    }
  }

  @Test
  void metadataFailureNeverDisplaysOrReplaysAnUncommittedChunkAndPreservesTheFile()
      throws Exception {
    var bus = new InMemoryAgentEventBus();
    var displayed = new ArrayList<AgentEvent>();
    bus.subscribe(displayed::add);
    try (var context = context(root, bus)) {
      bus.publish(event("good", PROJECT, false));
      Path log;
      try (var files =
          Files.list(root.resolve("projects").resolve(PROJECT).resolve("logs/activity-archives"))) {
        log = files.findFirst().orElseThrow();
      }
      byte[] committed = Files.readAllBytes(log);
      new StorageDatabase(root)
          .transaction(
              db -> {
                try (var sql = db.createStatement()) {
                  sql.execute(
                      "CREATE TRIGGER fail_diagnostic BEFORE UPDATE ON activity_segments BEGIN SELECT RAISE(ABORT,'diagnostic failed'); END");
                }
                return null;
              });
      assertThatThrownBy(() -> bus.publish(event("failed", PROJECT, false)))
          .hasRootCauseInstanceOf(java.sql.SQLException.class);
      assertThat(displayed).extracting(AgentEvent::id).containsExactly("good");
      var store = context.getBean(ProjectAgentEventStore.class);
      assertThat(store.findEvent(PROJECT, "good")).isPresent();
      assertThat(store.findEvent(PROJECT, "failed")).isEmpty();
      assertThat(store.lastSequence(PROJECT)).isEqualTo(1);
      try (var replay = new ReplayHandle(bus.subscribe("run", 0, ignored -> {}))) {
        assertThat(replay.value.replay()).extracting(AgentEvent::id).containsExactly("good");
      }
      byte[] retained = Files.readAllBytes(log);
      assertThat(retained.length).isGreaterThan(committed.length);
      assertThat(java.util.Arrays.copyOf(retained, committed.length)).isEqualTo(committed);
      // 既存のfail-closed契約：未確定の末尾は消さず、後続の追記・通常読み取りを拒否する。
      assertThatThrownBy(() -> context.getBean(ManagedActivityLog.class).read(PROJECT))
          .isInstanceOf(IllegalStateException.class);
      assertThatThrownBy(() -> bus.publish(event("later", PROJECT, false)))
          .isInstanceOf(IllegalStateException.class);
      assertThat(Files.readAllBytes(log)).isEqualTo(retained);
    }
  }

  private record ReplayHandle(AgentEventBus.ReplaySubscription value) implements AutoCloseable {
    @Override
    public void close() {
      value.subscription().unsubscribe();
    }
  }

  @Test
  void eventFailureLeavesNoDiagnosticAndNextPublishDoesNotDeliverTheFailedEvent() {
    var bus = new InMemoryAgentEventBus();
    var displayed = new ArrayList<AgentEvent>();
    bus.subscribe(displayed::add);
    try (var context = context(root, bus)) {
      var database = new StorageDatabase(root);
      database.transaction(
          db -> {
            try (var sql = db.createStatement()) {
              sql.execute(
                  "CREATE TRIGGER fail_event BEFORE INSERT ON agent_events BEGIN SELECT RAISE(ABORT,'event failed'); END");
            }
            return null;
          });
      assertThatThrownBy(() -> bus.publish(event("failed", PROJECT, true)))
          .hasRootCauseInstanceOf(java.sql.SQLException.class);
      assertThat(context.getBean(ManagedActivityLog.class).read(PROJECT)).isEmpty();
      assertThat(displayed).isEmpty();
      database.transaction(
          db -> {
            try (var sql = db.createStatement()) {
              sql.execute("DROP TRIGGER fail_event");
            }
            return null;
          });
      bus.publish(event("next", PROJECT, true));
      assertThat(displayed).extracting(AgentEvent::id).containsExactly("next");
      assertThat(context.getBean(ProjectAgentEventStore.class).readAfter(PROJECT, 0, 100))
          .extracting(AgentEvent::id)
          .containsExactly("next");
    }
  }

  @Test
  void projectScopesAndNonStreamingBoundariesRemainIndependent() {
    var bus = new InMemoryAgentEventBus();
    try (var context = context(root, bus)) {
      String other = UUID.randomUUID().toString();
      var factory = new AgentEventFactory(java.time.Clock.systemUTC());
      var owner = new dev.mikoto2000.rei.core.chat.AgentRunContext("run", "session", root, PROJECT);
      var started = factory.runStarted("run", "test", null).withOwnership(owner);
      var completed = factory.runCompleted("run", 1).withOwnership(owner);
      bus.publishBoundary(started);
      bus.publish(event("first", PROJECT, false));
      bus.publish(event("other", other, true));
      bus.publishBoundary(completed);
      var store = context.getBean(ProjectAgentEventStore.class);
      assertThat(store.readAfter(PROJECT, 0, 100))
          .extracting(AgentEvent::id)
          .containsExactly(started.id(), "first", completed.id());
      assertThat(store.readAfter(other, 0, 100))
          .extracting(AgentEvent::id)
          .containsExactly("other");
      var activity = context.getBean(ManagedActivityLog.class);
      assertThat(activity.read(PROJECT)).hasSize(3);
      assertThat(activity.read(other)).hasSize(1);
    }
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"before", "after"})
  void forcedProcessExitPreservesEveryDeliveredChunk(String crash) throws Exception {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    Path arguments = root.resolve("worker.args");
    Files.writeString(
        arguments,
        "-Dstdout.encoding=UTF-8\n-Dstderr.encoding=UTF-8\n-cp\n"
            + quote(classpath)
            + "\n"
            + StreamingPersistenceCrashWorker.class.getName()
            + "\n"
            + quote(root.toString())
            + "\n"
            + crash);
    String executable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    if (!System.getProperty("os.name").startsWith("Windows"))
      executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process =
        new ProcessBuilder(executable, "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(root.resolve("worker.log").toFile())
            .start();
    try {
      assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue())
          .withFailMessage(Files.readString(root.resolve("worker.log")))
          .isZero();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
        process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
      }
    }
    assertThat(Files.readString(root.resolve("delivered.txt"))).isEqualTo("good\n");
    try (var context = context(root, new InMemoryAgentEventBus())) {
      var store = context.getBean(ProjectAgentEventStore.class);
      assertThat(store.readAfter(PROJECT, 0, 100))
          .extracting(AgentEvent::id)
          .containsExactly("good");
      var activity = context.getBean(ManagedActivityLog.class);
      if (crash.equals("after")) assertThat(activity.read(PROJECT)).hasSize(1);
      else {
        assertThatThrownBy(() -> activity.read(PROJECT)).isInstanceOf(IllegalStateException.class);
        try (var files =
            Files.list(
                root.resolve("projects").resolve(PROJECT).resolve("logs/activity-archives"))) {
          String retained = Files.readString(files.findFirst().orElseThrow());
          assertThat(retained).contains("good", "pending").doesNotContain("private-stream-text");
        }
      }
    }
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
