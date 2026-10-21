package dev.mikoto2000.rei.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventPayload;
import dev.mikoto2000.rei.event.AgentEventType;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.event.MessageDeltaPayload;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.event.ProjectAgentEventStore;
import dev.mikoto2000.rei.event.ProjectAgentEventSubscriber;
import dev.mikoto2000.rei.event.SqliteProjectAgentEventStore;
import dev.mikoto2000.rei.event.ThinkingDeltaPayload;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/**
 * 回答生成Io性能測定の結果と状態の契約を検証するテスト。
 *
 * <p>Opt-in, real SQLite and managed Activity log; no LLM or user data.
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "rei.io.benchmark", matches = "true")
class AnswerGenerationIoBenchmarkTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 検証に使うプロジェクト。 */
  static final String PROJECT = "00000000-0000-0000-0000-000000000001";

  /** 検証条件を再現するための判定期間Io。 */
  public interface WindowsIo extends Library {
    /** 検証に使う起動単位の識別子。 */
    WindowsIo INSTANCE = Native.load("kernel32", WindowsIo.class);

    /** 検証用に、データ保存の共通処理で参照する現在プロセスを返す。 */
    Pointer GetCurrentProcess();

    /** 検証用に、データ保存の共通処理において、取得プロセスIoCountersに該当するか判定する。 */
    boolean GetProcessIoCounters(Pointer process, IoCounters counters);
  }

  /** 検証条件を再現するためのIoCounters。 */
  @Structure.FieldOrder({
    "readOperations",
    "writeOperations",
    "otherOperations",
    "readBytes",
    "writeBytes",
    "otherBytes"
  })
  public static class IoCounters extends Structure {
    /** 検証に使う読み取り操作一覧、書き込み操作一覧、その他操作一覧、読み取りバイト数、書き込みバイト数、その他バイト数。 */
    public long readOperations, writeOperations, otherOperations, readBytes, writeBytes, otherBytes;
  }

  /** {@code io} の条件を再現するための補助情報を返す。 */
  static IoCounters io() {
    if (!System.getProperty("os.name").startsWith("Windows")) return null;
    var counters = new IoCounters();
    if (!WindowsIo.INSTANCE.GetProcessIoCounters(WindowsIo.INSTANCE.GetCurrentProcess(), counters))
      return null;
    return counters;
  }

  /** 検証条件を再現するための利用可否の確認。 */
  static final class Probe implements Driver {
    /** 検証に使う委譲先。 */
    final Driver delegate;

    /** 検証に使う活動中。 */
    final AtomicBoolean active = new AtomicBoolean();

    /** 検証に使うtransactions、変更履歴、opens。 */
    final AtomicLong transactions = new AtomicLong(),
        commits = new AtomicLong(),
        opens = new AtomicLong();

    /** 検証条件に必要な値を受け取り、Probeを用意する。 */
    Probe(Driver delegate) {
      this.delegate = delegate;
    }

    /** {@code connect} の条件を再現するための補助情報を返す。 */
    public Connection connect(String url, Properties properties) throws SQLException {
      var connection = delegate.connect(url, properties);
      if (connection == null) return null;
      if (active.get()) opens.incrementAndGet();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                try {
                  Object result = method.invoke(connection, args);
                  if (active.get()) {
                    if (method.getName().equals("commit")) commits.incrementAndGet();
                    if (method.getName().equals("setAutoCommit") && Boolean.FALSE.equals(args[0]))
                      transactions.incrementAndGet();
                  }
                  return result;
                } catch (InvocationTargetException error) {
                  throw error.getCause();
                }
              });
    }

    /** {@code acceptsURL} の条件を再現するための補助情報を返す。 */
    public boolean acceptsURL(String url) throws SQLException {
      return delegate.acceptsURL(url);
    }

    /** 検証用に、データ保存の共通処理で参照するProperty付随情報を返す。 */
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties p) throws SQLException {
      return delegate.getPropertyInfo(url, p);
    }

    /** 検証用に、データ保存の共通処理で参照するMajor形式のバージョンを返す。 */
    public int getMajorVersion() {
      return delegate.getMajorVersion();
    }

    /** 検証用に、データ保存の共通処理で参照するMinor形式のバージョンを返す。 */
    public int getMinorVersion() {
      return delegate.getMinorVersion();
    }

    /** {@code jdbcCompliant} の条件を再現するための補助情報を返す。 */
    public boolean jdbcCompliant() {
      return delegate.jdbcCompliant();
    }

    /** 検証用に、データ保存の共通処理で参照する親ログの出力先を返す。 */
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }
  }

  /** 検証で使用するイベントを、指定された条件に合わせて用意する。 */
  AgentEvent event(int index, int run, boolean thinking, int length) {
    AgentEventPayload payload =
        thinking
            ? new ThinkingDeltaPayload("thought-" + run, "考".repeat(length))
            : new MessageDeltaPayload("answer-" + run, "あ".repeat(length));
    return new AgentEvent(
        "event-" + run + "-" + index,
        0,
        Instant.EPOCH.plusMillis(index),
        thinking ? AgentEventType.THINKING_DELTA : AgentEventType.MESSAGE_DELTA,
        1,
        "session-" + run,
        null,
        "run-" + run,
        null,
        null,
        payload,
        PROJECT);
  }

  /** データ保存の共通処理で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void measure() throws Exception {
    int offeredRate = Integer.parseInt(System.getProperty("rei.io.events-per-second", "50"));
    if (offeredRate != 0 && (offeredRate < 10 || offeredRate > 10_000))
      throw new IllegalArgumentException(
          "Benchmark rate must be 0 (maximum) or 10..10000 events/second");
    Class.forName("org.sqlite.JDBC");
    var original = DriverManager.getDriver("jdbc:sqlite:");
    var probe = new Probe(original);
    DriverManager.deregisterDriver(original);
    DriverManager.registerDriver(probe);
    var results = new ArrayList<Map<String, Object>>();
    boolean optimized = Boolean.getBoolean("rei.io.optimized");
    try {
      for (int repeat = 0; repeat < 3; repeat++)
        for (String scenario : List.of("short", "long", "high-frequency", "parallel")) {
          Path root = temporary.resolve(scenario + repeat);
          Files.createDirectories(root);
          AnnotationConfigApplicationContext context = null;
          StorageMigrationCoordinator coordinator = null;
          try {
            if (optimized) {
              context = new AnnotationConfigApplicationContext();
              context
                  .getEnvironment()
                  .getPropertySources()
                  .addFirst(new MapPropertySource("test", Map.of("rei.data-dir", root.toString())));
              context.register(StorageMigrationConfiguration.class);
              context.refresh();
            } else {
              coordinator = new StorageMigrationCoordinator(root);
              coordinator.prepare();
            }
            var registry = new StorageObjectRegistry(root);
            var project = new SqliteProjectAgentEventStore(root);
            var persistenceLatency = new ConcurrentLinkedQueue<Long>();
            var measuredProject =
                new ProjectAgentEventStore(root) {
                  /** {@code append} の条件を再現するための補助情報を返す。 */
                  @Override
                  public AgentEvent append(AgentEvent event) {
                    long start = System.nanoTime();
                    try {
                      return project.append(event);
                    } finally {
                      persistenceLatency.add(System.nanoTime() - start);
                    }
                  }
                };
            var activity = new ManagedActivityLog(registry);
            var profile =
                new ProfileEventLogStore() {
                  /** {@code append} の条件を再現するため、検証用の操作や状態を扱う。 */
                  @Override
                  public void append(AgentEvent event) {
                    long start = System.nanoTime();
                    try {
                      super.append(event);
                    } finally {
                      persistenceLatency.add(System.nanoTime() - start);
                    }
                  }
                };
            profile.setManagedActivityLog(activity);
            var bus = new InMemoryAgentEventBus();
            var displayed = new AtomicLong();
            try (var subscription = new ProjectAgentEventSubscriber(bus, measuredProject)) {
              bus.subscribe(profile);
              bus.subscribe(e -> displayed.incrementAndGet());
              int runs = scenario.equals("parallel") ? 4 : 1;
              int count = scenario.equals("short") ? 20 : scenario.equals("parallel") ? 250 : 1000;
              long[] latency = new long[runs * count];
              var applicationIo = new StorageIoObservation();
              probe.transactions.set(0);
              probe.commits.set(0);
              probe.opens.set(0);
              var sampler = new WindowsDiskSampler();
              var before = io();
              long started = System.nanoTime();
              probe.active.set(true);
              try (var executor = Executors.newFixedThreadPool(runs)) {
                var futures = new ArrayList<Future<?>>();
                for (int run = 0; run < runs; run++) {
                  final int r = run;
                  futures.add(
                      executor.submit(
                          () -> {
                            try (var observation = applicationIo.observe()) {
                              for (int i = 0; i < count; i++) {
                                if (offeredRate > 0)
                                  await(
                                      started
                                          + (long) (i * runs + r) * (1_000_000_000L / offeredRate));
                                long start = System.nanoTime();
                                bus.publish(
                                    event(
                                        i,
                                        r,
                                        scenario.equals("long") && i < count / 2,
                                        scenario.equals("long") ? 32 : 1));
                                latency[r * count + i] = System.nanoTime() - start;
                              }
                            }
                          }));
                }
                for (var future : futures) future.get(120, TimeUnit.SECONDS);
              } finally {
                probe.active.set(false);
                sampler.close();
              }
              long elapsed = System.nanoTime() - started;
              var after = io();
              long wal =
                  Files.exists(root.resolve("storage.db-wal"))
                      ? Files.size(root.resolve("storage.db-wal"))
                      : 0;
              Arrays.sort(latency);
              var row = new LinkedHashMap<String, Object>();
              row.put("scenario", scenario);
              row.put("repeat", repeat);
              row.put("mode", optimized ? "optimized" : "baseline");
              row.put("offeredEventsPerSecond", offeredRate);
              row.put("events", runs * count);
              row.put("displayed", displayed.get());
              row.put("persistenceCalls", runs * count * 2);
              row.put("transactions", probe.transactions.get());
              row.put("commits", probe.commits.get());
              row.put("connectionOpens", probe.opens.get());
              row.put("explicitForceCalls", applicationIo.forces.sum());
              row.put("activityWrites", applicationIo.writes.sum());
              row.put("activityWriteBytes", applicationIo.bytes.sum());
              row.put("elapsedMs", elapsed / 1e6);
              row.put("publishP95Ms", latency[(int) Math.ceil(latency.length * .95) - 1] / 1e6);
              row.put("publishMeanMs", Arrays.stream(latency).average().orElseThrow() / 1e6);
              row.put("walBytes", wal);
              var persistenceTimes =
                  persistenceLatency.stream().mapToLong(Long::longValue).sorted().toArray();
              row.put(
                  "persistenceP95Ms",
                  persistenceTimes[(int) Math.ceil(persistenceTimes.length * .95) - 1] / 1e6);
              row.put(
                  "persistenceMeanMs",
                  Arrays.stream(persistenceTimes).average().orElseThrow() / 1e6);
              if (before != null && after != null) {
                row.put("processWriteOperations", after.writeOperations - before.writeOperations);
                row.put("processWriteBytes", after.writeBytes - before.writeBytes);
              }
              row.put("queueDepth", 0);
              row.putAll(sampler.summary());
              results.add(row);
              assertThat(displayed.get()).isEqualTo(runs * count);
              assertThat(probe.commits.get()).isEqualTo(runs * count * 2);
              assertThat(activity.read(PROJECT)).hasSize(runs * count);
              assertThat(project.lastSequence(PROJECT)).isEqualTo(runs * count);
            }
          } finally {
            if (context != null) context.close();
            if (coordinator != null) coordinator.close();
          }
        }
    } finally {
      DriverManager.deregisterDriver(probe);
      DriverManager.registerDriver(original);
    }
    Path output =
        Path.of("target/answer-generation-io-" + (optimized ? "optimized" : "baseline") + ".json");
    Files.createDirectories(output.getParent());
    Files.writeString(
        output, StorageDatabase.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(results));
  }

  /** 検証用に、必要な状態が整ってから先へ進むため、回答生成Io性能測定検証を待つ。 */
  private static void await(long deadline) {
    long remaining;
    while ((remaining = deadline - System.nanoTime()) > 0) {
      if (Thread.currentThread().isInterrupted())
        throw new CancellationException("Benchmark interrupted");
      java.util.concurrent.locks.LockSupport.parkNanos(remaining);
    }
  }
}
