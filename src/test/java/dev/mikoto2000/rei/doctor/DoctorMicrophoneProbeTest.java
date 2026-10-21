package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.voice.AudioDevice;
import dev.mikoto2000.rei.voice.AudioDeviceService;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 環境診断マイク利用可否の確認の結果と状態の契約を検証するテスト。 */
class DoctorMicrophoneProbeTest {
  /** 検証に使うdevice。 */
  final AudioDevice device = new AudioDevice("id", "private name", "description", "vendor", "1");

  /** 実行環境の診断で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void onlyExplicitDeviceIsAcquiredAndEmptyConfigurationDoesNotEnumerate() {
    var acquired = new java.util.concurrent.atomic.AtomicInteger();
    var catalog = new java.util.concurrent.atomic.AtomicInteger();
    try (var probe =
        new DoctorMicrophoneProbe(
            new AudioDeviceService(
                () -> {
                  catalog.incrementAndGet();
                  return List.of(device);
                }),
            d -> acquired.incrementAndGet())) {
      assertEquals(
          DoctorMicrophoneProbe.Result.NOT_CONFIGURED,
          probe.check(null, Duration.ofMillis(100), () -> {}));
      assertEquals(0, catalog.get());
      assertEquals(
          DoctorMicrophoneProbe.Result.AVAILABLE,
          probe.check("id", Duration.ofSeconds(1), () -> {}));
      assertEquals(1, acquired.get());
      assertEquals(
          DoctorMicrophoneProbe.Result.UNAVAILABLE,
          probe.check("other", Duration.ofSeconds(1), () -> {}));
      assertEquals(1, acquired.get());
    }
  }

  /** 実行環境の診断の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void uninterruptibleDriverCannotCreateUnboundedAcquisitions() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    try (var probe =
        new DoctorMicrophoneProbe(
            new AudioDeviceService(() -> List.of(device)),
            d -> {
              entered.countDown();
              boolean done = false;
              while (!done)
                try {
                  release.await();
                  done = true;
                } catch (InterruptedException ignored) {
                }
            })) {
      assertEquals(
          DoctorMicrophoneProbe.Result.TIMEOUT,
          probe.check("id", Duration.ofMillis(100), () -> {}));
      assertTrue(entered.await(1, TimeUnit.SECONDS));
      assertEquals(
          DoctorMicrophoneProbe.Result.BUSY, probe.check("id", Duration.ofMillis(100), () -> {}));
    } finally {
      release.countDown();
    }
  }

  /** 実行環境の診断の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellationPropagatesBeforeAcquisition() {
    try (var probe =
        new DoctorMicrophoneProbe(
            new AudioDeviceService(
                () -> {
                  throw new AssertionError();
                }),
            d -> {
              throw new AssertionError();
            })) {
      assertThrows(
          CancellationException.class,
          () ->
              probe.check(
                  "id",
                  Duration.ofSeconds(1),
                  () -> {
                    throw new CancellationException();
                  }));
    }
  }
}
