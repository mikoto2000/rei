package dev.mikoto2000.rei.core.process;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** 外部プロセスの管理に必要な管理対象バックグラウンドプロセスを扱う。 */
class ManagedBackgroundProcess {
  /** プロセス識別子。 */
  final String processId;

  /** 所有者。 */
  final dev.mikoto2000.rei.core.chat.AgentRunContext owner;

  /** プロセス。 */
  final Process process;

  /** コマンド行。 */
  final List<String> commandLine;

  /** 作業ディレクトリ。 */
  final Path workingDirectory;

  /** 標準出力。 */
  final BoundedLineBuffer stdout;

  /** 標準エラー出力。 */
  final BoundedLineBuffer stderr;

  /** 状態。 */
  final AtomicReference<BackgroundProcessStatus> status =
      new AtomicReference<>(BackgroundProcessStatus.STARTING);

  /** 終了コード。 */
  final AtomicReference<Integer> exitCode = new AtomicReference<>();

  /** 終端イベント公開。 */
  final AtomicBoolean terminalEventPublished = new AtomicBoolean(false);

  /** 開始日時。 */
  final Instant startedAt;

  /** 開始日時ナノ秒数。 */
  final long startedAtNanos;

  /** 終了日時。 */
  volatile Instant endedAt;

  /** 標準出力読み取り元。 */
  volatile Future<?> stdoutReader;

  /** 標準エラー出力読み取り元。 */
  volatile Future<?> stderrReader;

  /** 指定された値や利用先を使って、ManagedBackgroundProcessを初期化する。 */
  ManagedBackgroundProcess(
      String processId,
      Process process,
      List<String> commandLine,
      Path workingDirectory,
      int logLineCapacity,
      Instant startedAt,
      long startedAtNanos) {
    this.processId = processId;
    this.owner = dev.mikoto2000.rei.core.chat.AgentRunScope.current();
    this.process = process;
    this.commandLine = List.copyOf(commandLine);
    this.workingDirectory = workingDirectory;
    this.stdout = new BoundedLineBuffer(logLineCapacity);
    this.stderr = new BoundedLineBuffer(logLineCapacity);
    this.startedAt = startedAt;
    this.startedAtNanos = startedAtNanos;
  }
}
