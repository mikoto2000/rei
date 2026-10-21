package dev.mikoto2000.rei.doctor;

import dev.mikoto2000.rei.voice.AudioDevice;
import dev.mikoto2000.rei.voice.AudioDeviceService;
import dev.mikoto2000.rei.voice.JavaSoundMicrophoneCapture;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * 実行環境の診断に必要な環境診断マイク利用可否の確認を扱う。
 *
 * <p>One bounded outstanding acquisition; timeout never starts another blocked native driver.
 */
@Component
public class DoctorMicrophoneProbe implements AutoCloseable {
  /** 実行環境の診断における結果の選択肢。 */
  public enum Result {
    /** 利用可能。 */
    AVAILABLE,
    /** 否定設定済み。 */
    NOT_CONFIGURED,
    /** 利用不可。 */
    UNAVAILABLE,
    /** タイムアウト。 */
    TIMEOUT,
    /** 使用中。 */
    BUSY
  }

  /** 実行環境の診断に必要な情報の取得を扱う。 */
  @FunctionalInterface
  public interface Acquisition {
    /** 実行環境の診断に関する確保を処理する。 */
    void acquire(AudioDevice device) throws Exception;
  }

  /** 利用可能な音声機器を管理する窓口。 */
  private final AudioDeviceService devices;

  /** 情報の取得。 */
  private final Acquisition acquisition;

  /** 利用可能。 */
  private final Semaphore available = new Semaphore(1);

  /** 実行の担当。 */
  private final ExecutorService worker =
      Executors.newSingleThreadExecutor(
          Thread.ofPlatform().daemon().name("doctor-microphone").factory());

  /** 実行環境の診断を利用するため、指定を補って共通の初期化へ引き渡す。 */
  @org.springframework.beans.factory.annotation.Autowired
  public DoctorMicrophoneProbe(AudioDeviceService devices) {
    this(devices, new JavaSoundMicrophoneCapture()::acquireWithoutRecording);
  }

  /** 指定された値や利用先を使って、DoctorMicrophoneProbeを初期化する。 */
  public DoctorMicrophoneProbe(AudioDeviceService devices, Acquisition acquisition) {
    this.devices = devices;
    this.acquisition = acquisition;
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public Result check(String deviceId, Duration timeout, Runnable checkActive) {
    checkActive.run();
    if (timeout == null
        || timeout.compareTo(Duration.ofMillis(100)) < 0
        || timeout.compareTo(Duration.ofSeconds(30)) > 0)
      throw new IllegalArgumentException("Microphone timeout must be 100ms to 30s");
    if (deviceId == null || deviceId.isBlank()) return Result.NOT_CONFIGURED;
    if (!available.tryAcquire()) return Result.BUSY;
    var started = new AtomicBoolean();
    var released = new AtomicBoolean();
    var stop = new AtomicBoolean();
    Runnable release =
        () -> {
          // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
          if (released.compareAndSet(false, true)) available.release();
        };
    Future<Result> pending;
    try {
      pending =
          worker.submit(
              () -> {
                started.set(true);
                try {
                  if (stop.get() || Thread.currentThread().isInterrupted())
                    return Result.UNAVAILABLE;
                  var matches =
                      devices.devices().stream().filter(d -> d.id().equals(deviceId)).toList();
                  if (matches.size() != 1) return Result.UNAVAILABLE;
                  if (stop.get() || Thread.currentThread().isInterrupted())
                    return Result.UNAVAILABLE;
                  acquisition.acquire(matches.getFirst());
                  return Result.AVAILABLE;
                } finally {
                  release.run();
                }
              });
    } catch (RuntimeException rejected) {
      release.run();
      return Result.UNAVAILABLE;
    }
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (true) {
        checkActive.run();
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) return Result.TIMEOUT;
        try {
          var result =
              pending.get(
                  Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)), TimeUnit.NANOSECONDS);
          checkActive.run();
          return result;
        } catch (TimeoutException slice) {
        }
      }
    } catch (InterruptedException interrupted) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw new CancellationException("Microphone diagnosis cancelled");
    } catch (ExecutionException unavailable) {
      return Result.UNAVAILABLE;
    } finally {
      stop.set(true);
      pending.cancel(true);
      if (!started.get()) release.run();
    }
  }

  /** 実行環境の診断で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  @jakarta.annotation.PreDestroy
  public void close() {
    worker.shutdownNow();
  }
}
