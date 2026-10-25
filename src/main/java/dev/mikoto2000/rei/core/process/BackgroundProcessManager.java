package dev.mikoto2000.rei.core.process;

import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import dev.mikoto2000.rei.temporal.MonotonicTimeSource;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** バックグラウンドプロセスの状態と操作を管理する。 */
@Component
public class BackgroundProcessManager {
  /** ログ行許容件数。 */
  private static final int LOG_LINE_CAPACITY = 200;

  /** 既定末尾行一覧。 */
  private static final int DEFAULT_TAIL_LINES = 100;

  /** 強制終了判定を猶予する期間。 */
  private static final Duration KILL_GRACE_PERIOD = Duration.ofSeconds(5);

  /** システムシェルサービス。 */
  private final SystemShellService systemShellService;

  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** monotonic時刻取得元。 */
  private final MonotonicTimeSource monotonicTimeSource;

  /** 実行状況の通知を生成する窓口。 */
  private final AgentEventFactory eventFactory;

  /** 実行状況の通知先。 */
  private final AgentEventPublisher eventPublisher;

  /** プロセス一覧。 */
  private final ConcurrentMap<String, ManagedBackgroundProcess> processes =
      new ConcurrentHashMap<>();

  /** 実行先。 */
  private final ExecutorService executor =
      Executors.newCachedThreadPool(
          r -> {
            Thread thread = new Thread(r, "rei-background-process");
            thread.setDaemon(true);
            return thread;
          });

  /** 指定された値や利用先を使って、BackgroundProcessManagerを初期化する。 */
  @Autowired
  public BackgroundProcessManager(
      SystemShellService systemShellService,
      Clock clock,
      MonotonicTimeSource monotonicTimeSource,
      AgentEventFactory eventFactory,
      AgentEventPublisher eventPublisher) {
    this.systemShellService = systemShellService;
    this.clock = clock;
    this.monotonicTimeSource = monotonicTimeSource;
    this.eventFactory = eventFactory;
    this.eventPublisher = eventPublisher;
  }

  /** 外部プロセスの管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public BackgroundProcessManager(
      SystemShellService systemShellService, Clock clock, MonotonicTimeSource monotonicTimeSource) {
    this(
        systemShellService,
        clock,
        monotonicTimeSource,
        new AgentEventFactory(clock),
        new InMemoryAgentEventBus());
  }

  /** 外部プロセスの管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public BackgroundProcessManager(SystemShellService systemShellService) {
    this(systemShellService, Clock.systemDefaultZone(), MonotonicTimeSource.system());
  }

  /** 外部プロセスの管理で用いるspawnシェルを返す。 */
  public BackgroundProcessSnapshot spawnShell(String command, Path workingDirectory) {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command は空にできません");
    }
    String shell = systemShellService.resolveShell(System.getenv(), System.getProperty("os.name"));
    return spawnCommandLine(systemShellService.shellCommandLine(shell, command), workingDirectory);
  }

  /** 外部プロセスの管理で用いるspawnコマンド行を返す。 */
  BackgroundProcessSnapshot spawnCommandLine(List<String> commandLine, Path workingDirectory) {
    String processId = "proc-" + UUID.randomUUID().toString().substring(0, 8);
    try {
      Process process =
          new ProcessBuilder(commandLine).directory(workingDirectory.toFile()).start();
      ManagedBackgroundProcess managedProcess =
          new ManagedBackgroundProcess(
              processId,
              process,
              commandLine,
              workingDirectory,
              LOG_LINE_CAPACITY,
              Instant.now(clock),
              monotonicTimeSource.nanoTime());
      processes.put(processId, managedProcess);
      managedProcess.status.set(BackgroundProcessStatus.RUNNING);
      eventPublisher.publish(
          eventFactory.backgroundProcessStarted(
              processId,
              process.pid(),
              String.join(" ", commandLine),
              workingDirectory.toAbsolutePath().normalize().toString()));
      managedProcess.stdoutReader =
          executor.submit(() -> readLines(process.getInputStream(), managedProcess.stdout));
      managedProcess.stderrReader =
          executor.submit(() -> readLines(process.getErrorStream(), managedProcess.stderr));
      CompletableFuture.runAsync(() -> watchProcess(managedProcess), executor);
      return snapshot(managedProcess, DEFAULT_TAIL_LINES, "started");
    } catch (IOException e) {
      eventPublisher.publish(
          eventFactory.backgroundProcessFailed(processId, String.join(" ", commandLine), e));
      return new BackgroundProcessSnapshot(
          processId,
          -1,
          BackgroundProcessStatus.FAILED,
          null,
          null,
          null,
          0.0d,
          List.of(),
          List.of(),
          false,
          e.getMessage());
    }
  }

  /** 同じ管理対象 Process を指定時間だけ待つ。timeout しても終了しない。 */
  public BackgroundProcessSnapshot await(String processId, Duration wait)
      throws InterruptedException {
    ManagedBackgroundProcess managedProcess = processes.get(processId);
    if (managedProcess == null) {
      return new BackgroundProcessSnapshot(
          processId,
          -1,
          BackgroundProcessStatus.FAILED,
          null,
          null,
          null,
          0.0d,
          List.of(),
          List.of(),
          false,
          "process not found");
    }
    boolean completed =
        managedProcess.process.waitFor(Math.max(0L, wait.toMillis()), TimeUnit.MILLISECONDS);
    if (!completed && !managedProcess.process.isAlive()) completed = true;
    if (completed) {
      managedProcess.exitCode.compareAndSet(null, managedProcess.process.exitValue());
      managedProcess.endedAt =
          managedProcess.endedAt == null ? Instant.now(clock) : managedProcess.endedAt;
      managedProcess.status.compareAndSet(
          BackgroundProcessStatus.RUNNING, BackgroundProcessStatus.EXITED);
      awaitReader(managedProcess.stdoutReader);
      awaitReader(managedProcess.stderrReader);
      publishTerminalEvent(managedProcess);
      return snapshot(managedProcess, DEFAULT_TAIL_LINES, "completed");
    }
    return snapshot(managedProcess, DEFAULT_TAIL_LINES, "running");
  }

  /** foreground 解決した終了済み Process を registry から除く。 */
  public void forgetCompleted(String processId) {
    processes.computeIfPresent(
        processId, (id, process) -> process.process.isAlive() ? process : null);
  }

  /** 必要な状態が整ってから先へ進むため、読み取り元を待つ。 */
  private void awaitReader(java.util.concurrent.Future<?> reader) {
    if (reader == null) return;
    try {
      reader.get(1, TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException ignored) {
    } catch (InterruptedException exception) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
  }

  /** 外部プロセスの管理で用いる状態自分が所有する対象を返す。 */
  public BackgroundProcessSnapshot statusOwned(
      String processId, String project, String session, Path root) {
    var process = processes.get(processId);
    if (process == null
        || process.owner == null
        || !java.util.Objects.equals(process.owner.projectId(), project)
        || !process.owner.conversationId().equals(session)
        || !process.owner.projectRoot().equals(root))
      return new BackgroundProcessSnapshot(
          processId,
          -1,
          BackgroundProcessStatus.FAILED,
          null,
          null,
          null,
          0,
          List.of(),
          List.of(),
          false,
          "process ownership unavailable");
    return status(processId, 0);
  }

  /** 対象の現在の状態を、照会用の情報として返す。 */
  public BackgroundProcessSnapshot status(String processId, Integer tailLines) {
    ManagedBackgroundProcess managedProcess = processes.get(processId);
    if (managedProcess == null) {
      return new BackgroundProcessSnapshot(
          processId,
          -1,
          BackgroundProcessStatus.FAILED,
          null,
          null,
          null,
          0.0d,
          List.of(),
          List.of(),
          false,
          "process not found");
    }
    return snapshot(managedProcess, normalizeTailLines(tailLines), "ok");
  }

  /** 外部プロセスの管理で用いる強制終了を返す。 */
  public BackgroundProcessSnapshot kill(String processId) {
    ManagedBackgroundProcess managedProcess = processes.get(processId);
    if (managedProcess == null) {
      return new BackgroundProcessSnapshot(
          processId,
          -1,
          BackgroundProcessStatus.FAILED,
          null,
          null,
          null,
          0.0d,
          List.of(),
          List.of(),
          false,
          "process not found");
    }
    managedProcess.status.set(BackgroundProcessStatus.KILLED);
    terminateProcessTree(managedProcess.process.toHandle());
    waitForMainProcess(managedProcess, Duration.ofSeconds(2));
    if (!managedProcess.process.isAlive()) {
      managedProcess.endedAt =
          managedProcess.endedAt == null ? Instant.now(clock) : managedProcess.endedAt;
    }
    publishTerminalEvent(managedProcess);
    return snapshot(managedProcess, DEFAULT_TAIL_LINES, "killed");
  }

  /** 外部プロセスの管理に関する終了を処理する。 */
  @PreDestroy
  public void shutdown() {
    processes.keySet().forEach(this::kill);
    executor.shutdownNow();
  }

  /** 外部プロセスの管理に関する監視プロセスを処理する。 */
  private void watchProcess(ManagedBackgroundProcess managedProcess) {
    try {
      int exitCode = managedProcess.process.waitFor();
      managedProcess.exitCode.set(exitCode);
      managedProcess.endedAt = Instant.now(clock);
      if (managedProcess.status.get() == BackgroundProcessStatus.KILLED) {
        publishTerminalEvent(managedProcess);
        return;
      }
      managedProcess.status.compareAndSet(
          BackgroundProcessStatus.RUNNING, BackgroundProcessStatus.EXITED);
      managedProcess.status.compareAndSet(
          BackgroundProcessStatus.STARTING, BackgroundProcessStatus.EXITED);
      publishTerminalEvent(managedProcess);
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
  }

  /** 外部プロセスの管理に関する終了プロセス構造を処理する。 */
  private List<ProcessHandle> terminateProcessTree(ProcessHandle root) {
    List<ProcessHandle> handles =
        Stream.concat(root.descendants(), Stream.of(root)).distinct().toList();
    handles.forEach(
        handle -> {
          if (handle.isAlive()) {
            handle.destroy();
          }
        });
    waitForExit(handles, KILL_GRACE_PERIOD);
    handles.forEach(
        handle -> {
          if (handle.isAlive()) {
            handle.destroyForcibly();
          }
        });
    waitForExit(handles, Duration.ofSeconds(1));
    return handles;
  }

  /** 呼び出しが所有する前面 Process だけを既存ポリシーで終了し、実際の終了を待つ。 */
  public void terminateOwned(Process process) {
    boolean interrupted = Thread.interrupted();
    try {
      var handles = terminateProcessTree(process.toHandle());
      for (var handle : handles) {
        while (handle.isAlive()) {
          try { handle.onExit().get(); }
          catch (InterruptedException stop) { interrupted = true; }
          catch (java.util.concurrent.ExecutionException failure) {
            throw new IllegalStateException("Owned process termination could not be confirmed", failure);
          }
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  /** AUTO の前面待機中に停止された、まだ引き渡していない Process を終了する。 */
  public void cancelOwned(String processId) {
    var owned = processes.get(processId);
    if (owned == null) return;
    terminateOwned(owned.process);
    forgetCompleted(processId);
  }

  /** 必要な状態が整ってから先へ進むため、対象終了を待つ。 */
  private void waitForExit(List<ProcessHandle> handles, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (handles.stream().noneMatch(ProcessHandle::isAlive)) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /** 必要な状態が整ってから先へ進むため、対象Mainプロセスを待つ。 */
  private void waitForMainProcess(ManagedBackgroundProcess managedProcess, Duration timeout) {
    try {
      if (managedProcess.process.waitFor(
          timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
        managedProcess.exitCode.compareAndSet(null, managedProcess.process.exitValue());
      }
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
    }
  }

  /** 必要な内容を参照できるよう、行一覧を読み取る。 */
  private void readLines(InputStream stream, BoundedLineBuffer buffer) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        buffer.add(line);
      }
    } catch (IOException e) {
      buffer.add("[read error] " + e.getMessage());
    }
  }

  /** 現在の状態を、後から参照できる記録として取り出す。 */
  private BackgroundProcessSnapshot snapshot(
      ManagedBackgroundProcess managedProcess, int tailLines, String message) {
    return new BackgroundProcessSnapshot(
        managedProcess.processId,
        managedProcess.process.pid(),
        managedProcess.status.get(),
        managedProcess.exitCode.get(),
        managedProcess.startedAt,
        managedProcess.endedAt,
        elapsedSeconds(managedProcess),
        managedProcess.stdout.tail(tailLines),
        managedProcess.stderr.tail(tailLines),
        true,
        message);
  }

  /** 実行状況を利用先で把握できるよう、終端イベントを通知する。 */
  private void publishTerminalEvent(ManagedBackgroundProcess managedProcess) {
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!managedProcess.terminalEventPublished.compareAndSet(false, true)) {
      return;
    }
    if (managedProcess.status.get() == BackgroundProcessStatus.KILLED) {
      eventPublisher.publish(
          eventFactory.backgroundProcessKilled(
              managedProcess.processId,
              managedProcess.process.pid(),
              managedProcess.exitCode.get(),
              elapsedSeconds(managedProcess)));
      return;
    }
    eventPublisher.publish(
        eventFactory.backgroundProcessCompleted(
            managedProcess.processId,
            managedProcess.process.pid(),
            managedProcess.exitCode.get(),
            elapsedSeconds(managedProcess)));
  }

  /** 外部プロセスの管理で用いる経過時間秒数を返す。 */
  private double elapsedSeconds(ManagedBackgroundProcess managedProcess) {
    return Math.max(
        0.0d, (monotonicTimeSource.nanoTime() - managedProcess.startedAtNanos) / 1_000_000_000.0d);
  }

  /** 同じ意味の指定を共通に扱えるよう、末尾行一覧の表記をそろえる。 */
  private int normalizeTailLines(Integer tailLines) {
    if (tailLines == null || tailLines <= 0) {
      return DEFAULT_TAIL_LINES;
    }
    return Math.min(tailLines, LOG_LINE_CAPACITY);
  }
}
