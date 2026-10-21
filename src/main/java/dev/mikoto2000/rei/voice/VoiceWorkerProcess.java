package dev.mikoto2000.rei.voice;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 音声による対話に必要な音声実行の担当プロセスを扱う。
 *
 * <p>Bounded binary IPC. Native output never reaches the parent terminal; only this owned child is
 * stopped.
 */
public final class VoiceWorkerProcess implements AutoCloseable {
  /** MAGIC／形式のバージョン／最大サンプル一覧／最大テキストバイト数。 */
  public static final int MAGIC = 0x52454956,
      VERSION = 1,
      MAX_SAMPLES = 448000,
      MAX_TEXT_BYTES = 65536;

  /** プロセス。 */
  private final Process process;

  /** 入力。 */
  private final DataInputStream input;

  /** 出力。 */
  private final DataOutputStream output;

  /** 要求タイムアウト。 */
  private final Duration requestTimeout;

  /** 入出力を担当する実行先。 */
  private final ExecutorService io =
      Executors.newSingleThreadExecutor(
          r -> {
            var t = new Thread(r, "voice-worker-ipc");
            t.setDaemon(true);
            return t;
          });

  /** 終了済み。 */
  private final AtomicBoolean closed = new AtomicBoolean();

  /** 標準エラー出力バイト数。 */
  private final AtomicLong stderrBytes = new AtomicLong();

  /** 標準エラー出力。 */
  private final Thread stderr;

  /** 使用中。 */
  private volatile boolean busy;

  /** 指定された値や利用先を使って、VoiceWorkerProcessを初期化する。 */
  private VoiceWorkerProcess(Process process, Duration requestTimeout) {
    this.process = process;
    this.requestTimeout = requestTimeout;
    input = new DataInputStream(new BufferedInputStream(process.getInputStream(), 65536));
    output = new DataOutputStream(new BufferedOutputStream(process.getOutputStream(), 65536));
    stderr =
        new Thread(
            () -> {
              try (var stream = process.getErrorStream()) {
                byte[] buffer = new byte[8192];
                int size;
                while ((size = stream.read(buffer)) != -1) stderrBytes.addAndGet(size);
              } catch (IOException ignored) {
              }
            },
            "voice-worker-stderr");
    stderr.setDaemon(true);
    stderr.start();
  }

  /** 要求された処理を開始する。 */
  public static VoiceWorkerProcess start(
      List<String> command, Duration startupTimeout, Duration requestTimeout) throws IOException {
    Objects.requireNonNull(command);
    if (command.isEmpty()
        || startupTimeout.isZero()
        || startupTimeout.isNegative()
        || requestTimeout.isZero()
        || requestTimeout.isNegative())
      throw new IllegalArgumentException("Invalid worker command or deadline");
    var worker =
        new VoiceWorkerProcess(new ProcessBuilder(List.copyOf(command)).start(), requestTimeout);
    worker.exchange(
        () -> {
          if (worker.input.readInt() != MAGIC || worker.input.readInt() != VERSION)
            throw new IOException("Invalid voice worker handshake");
          return null;
        },
        startupTimeout);
    return worker;
  }

  /** 音声による対話で用いるprobabilityを返す。 */
  public synchronized float probability(float[] frame) throws IOException {
    VoicePcm.validateFrame(frame);
    float[] copy = frame.clone();
    return exchange(
        () -> {
          write(1, copy);
          if (input.readInt() != 1) throw new IOException("Invalid VAD response");
          float value = input.readFloat();
          if (!Float.isFinite(value) || value < 0 || value > 1)
            throw new IOException("Invalid VAD probability");
          return value;
        },
        requestTimeout);
  }

  /** 音声による対話で用いるrecognizeを返す。 */
  public synchronized String recognize(float[] samples) throws IOException {
    if (samples == null || samples.length < 1 || samples.length > MAX_SAMPLES)
      throw new IllegalArgumentException("Invalid worker sample count");
    float[] copy = samples.clone();
    for (float sample : copy)
      if (!Float.isFinite(sample) || Math.abs(sample) > 1)
        throw new IllegalArgumentException("Invalid worker PCM");
    return exchange(
        () -> {
          write(2, copy);
          if (input.readInt() != 2) throw new IOException("Invalid ASR response");
          int size = input.readInt();
          if (size < 0 || size > MAX_TEXT_BYTES) throw new IOException("Invalid ASR response size");
          byte[] bytes = new byte[size];
          input.readFully(bytes);
          return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        },
        requestTimeout);
  }

  /** 指定された内容を書き込み先へ反映する。 */
  private void write(int operation, float[] samples) throws IOException {
    output.writeInt(operation);
    output.writeInt(samples.length);
    for (float sample : samples) output.writeFloat(sample);
    output.flush();
  }

  /** 音声による対話で用いるexchangeを返す。 */
  private <T> T exchange(Callable<T> action, Duration timeout) throws IOException {
    if (closed.get()) throw new IOException("Voice worker is closed");
    busy = true;
    Future<T> task = null;
    try {
      task = io.submit(action);
      return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      close();
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new IOException("Voice worker request interrupted");
    } catch (ExecutionException | TimeoutException | RejectedExecutionException failure) {
      close();
      throw new IOException("Voice worker failed or exceeded its deadline");
    } finally {
      if (task != null && !task.isDone()) task.cancel(true);
      busy = false;
    }
  }

  /** 音声による対話で用いる標準エラー出力バイト数を返す。 */
  public long stderrBytes() {
    return stderrBytes.get();
  }

  /** 音声による対話において、稼働中かどうかを判定する。 */
  public boolean isAlive() {
    return process.isAlive();
  }

  /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
  public void close() {
    // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
    if (!closed.compareAndSet(false, true)) return;
    boolean interrupted = Thread.interrupted();
    try {
      if (!busy)
        try {
          output.close();
        } catch (IOException ignored) {
        }
      else process.destroy();
      try {
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
      } catch (InterruptedException stop) {
        interrupted = true;
        process.destroyForcibly();
      }
      // destroyForcibly is asynchronous. Wait within one monotonic budget even when
      // cancellation has already interrupted this caller, then restore its flag.
      if (process.isAlive()) {
        process.destroyForcibly();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (process.isAlive()) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) break;
          try {
            process.waitFor(remaining, TimeUnit.NANOSECONDS);
          } catch (InterruptedException stop) {
            interrupted = true;
          }
        }
      }
    } finally {
      process.destroyForcibly();
      io.shutdownNow();
      try {
        input.close();
      } catch (IOException ignored) {
      }
      try {
        output.close();
      } catch (IOException ignored) {
      }
      try {
        stderr.join(250);
      } catch (InterruptedException stop) {
        interrupted = true;
      }
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
