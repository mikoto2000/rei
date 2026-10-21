package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.ProjectClient;
import dev.mikoto2000.rei.core.project.ProjectClientScope;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import picocli.CommandLine;

/** 音声自動開始の結果と状態の契約を検証するテスト。 */
class VoiceAutoStartTest {
  /** 検証に使うこの機能の動作設定。 */
  final VoiceProperties properties = new VoiceProperties();

  /** 検証に使うモデル一覧。 */
  final VoiceModelManager models = mock(VoiceModelManager.class);

  /** 検証に使うシェル。 */
  final ShellConversationService shell = mock(ShellConversationService.class);

  /** 検証に使う配信。 */
  final VoiceDeliveryService delivery = mock(VoiceDeliveryService.class);

  /** 検証に使うクライアント。 */
  final ProjectClient client = mock(ProjectClient.class);

  /** 検証に使う対象。 */
  final ConversationTarget target =
      new ConversationTarget(
          new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");

  /** 検証に使うdevice。 */
  final AudioDevice device = new AudioDevice("test-device", "test", "input", "vendor", "1");

  /** 検証に使うdevices。 */
  final AudioDeviceService devices = new AudioDeviceService(() -> List.of(device));

  /** 検証に使う出力。 */
  final StringWriter output = new StringWriter();

  /** 検証に使う書き込み先。 */
  final PrintWriter writer = new PrintWriter(output, true);

  /** 検証に使うバックエンドOpens。 */
  final AtomicInteger backendOpens = new AtomicInteger();

  /** 検証に使う取得記録Opens。 */
  final AtomicInteger captureOpens = new AtomicInteger();

  /** 検証条件に必要な値を受け取り、VoiceAutoStartTestを用意する。 */
  VoiceAutoStartTest() throws Exception {
    properties.setAutoStart(true);
    devices.select(device.id());
    when(models.readyDirectory()).thenReturn(Path.of("unused-fake-models"));
    when(shell.captureClient()).thenReturn(client);
    when(shell.captureTarget()).thenReturn(target);
    when(shell.isSelected(client, target)).thenReturn(true);
    when(client.open()).thenAnswer(invocation -> ProjectClientScope.open(client));
  }

  /** {@code coordinator} の条件を再現するための補助情報を返す。 */
  VoiceInputCoordinator coordinator() {
    return coordinator(
        settings -> {
          backendOpens.incrementAndGet();
          return new VoiceBackend(
              new VoiceActivityDetector() {
                /** {@code probability} の条件を再現するための補助情報を返す。 */
                public float probability(float[] frame) {
                  return 0;
                }

                /** 検証で利用した対象の終了を扱う。 */
                public void close() {}
              },
              new SpeechRecognizer() {
                /** {@code recognize} の条件を再現するための補助情報を返す。 */
                public String recognize(SpeechSegment segment) {
                  return "unused";
                }

                /** 検証で利用した対象の終了を扱う。 */
                public void close() {}
              });
        });
  }

  /** {@code coordinator} の条件を再現するための補助情報を返す。 */
  VoiceInputCoordinator coordinator(VoiceBackendFactory backend) {
    return new VoiceInputCoordinator(
        selected -> {
          captureOpens.incrementAndGet();
          return new MicrophoneCaptureService.FrameSource() {
            /** 検証用に、必要な内容を参照できるよう、音声フレームを読み取る。 */
            public float[] readFrame() throws InterruptedException {
              Thread.sleep(60000);
              return null;
            }

            /** 検証で利用した対象の終了を扱う。 */
            public void close() {}
          };
        },
        backend,
        input -> {},
        new VoiceEventPublisher(),
        Clock.systemUTC());
  }

  /** {@code command} の条件を再現するための補助情報を返す。 */
  VoiceCommand command(VoiceInputCoordinator voice) {
    var command = new VoiceCommand(voice, devices, properties, shell, models, delivery);
    var cli = new CommandLine(command);
    cli.setOut(writer);
    cli.setErr(writer);
    return command;
  }

  /** {@code state} の条件を再現するため、検証用の操作や状態を扱う。 */
  void state(VoiceInputCoordinator voice, VoiceInputCoordinator.State expected) {
    await()
        .atMost(Duration.ofSeconds(3))
        .untilAsserted(() -> assertThat(voice.state()).isEqualTo(expected));
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void configurationIsOptInAndBindsBothBooleanValues() {
    assertThat(new VoiceProperties().isAutoStart()).isFalse();
    for (boolean enabled : new boolean[] {false, true}) {
      var binder =
          new Binder(new MapConfigurationPropertySource(Map.of("rei.voice.auto-start", enabled)));
      assertThat(binder.bind("rei.voice", Bindable.of(VoiceProperties.class)).get().isAutoStart())
          .isEqualTo(enabled);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void constructionAndDisabledStartupDoNotTouchModelsOrMicrophone() {
    try (var voice = coordinator()) {
      var command = command(voice);
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(models, shell, delivery);
      properties.setAutoStart(false);
      command.autoStart(true, writer::println);
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      verifyNoInteractions(models, shell, delivery);
      assertThat(backendOpens).hasValue(0);
      assertThat(captureOpens).hasValue(0);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void noninteractiveShellNeverStartsEvenWhenEnabled() {
    try (var voice = coordinator()) {
      command(voice).autoStart(false, writer::println);
      verifyNoInteractions(models, shell, delivery);
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
    }
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void enabledStartupIsOneShotAndManualOffAndOnStillWork() {
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      state(voice, VoiceInputCoordinator.State.LISTENING);
      command.autoStart(true, writer::println);
      verify(delivery).bind(client, target);
      verify(client).open();
      assertThat(backendOpens).hasValue(1);
      assertThat(captureOpens).hasValue(1);
      assertThat(command.off()).isZero();
      state(voice, VoiceInputCoordinator.State.OFF);
      command.autoStart(true, writer::println);
      assertThat(command.on()).isZero();
      assertThat(backendOpens).hasValue(2);
      assertThat(captureOpens).hasValue(2);
      verify(models, never()).install(any());
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingModelsLeaveVoiceOffAndDoNotDownload() throws Exception {
    when(models.readyDirectory()).thenThrow(new IOException("missing"));
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(output.toString()).contains("モデル一式"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0);
      assertThat(captureOpens).hasValue(0);
      command.autoStart(true, writer::println);
      verify(models, never()).install(any());
      verify(models).readyDirectory();
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void modelDownloadInProgressDoesNotTriggerRecordingOrAnotherDownload() {
    when(models.busy()).thenReturn(true);
    try (var voice = coordinator()) {
      command(voice).autoStart(true, writer::println);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(output.toString()).contains("モデル取得中"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0);
      assertThat(captureOpens).hasValue(0);
      verify(models, never()).install(any());
    }
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void manualOffBeforeShellReadySuppressesAutomaticAttempt() {
    try (var voice = coordinator()) {
      var command = command(voice);
      assertThat(command.off()).isZero();
      command.autoStart(true, writer::println);
      verifyNoInteractions(models, shell);
      assertThat(backendOpens).hasValue(0);
      assertThat(captureOpens).hasValue(0);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void unselectedMicrophoneIsExplainedWithoutBackendStartup() {
    devices.invalidateSelection();
    try (var voice = coordinator()) {
      command(voice).autoStart(true, writer::println);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(output.toString()).contains("Select a microphone"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0);
      assertThat(captureOpens).hasValue(0);
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void nativeStartupFailureReturnsToOffWithoutStoppingTheShell() {
    try (var voice =
        coordinator(
            settings -> {
              throw new UnsatisfiedLinkError("fake native failure");
            })) {
      command(voice).autoStart(true, writer::println);
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(output.toString()).contains("自動開始できませんでした"));
      state(voice, VoiceInputCoordinator.State.OFF);
      assertThat(captureOpens).hasValue(0);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void offOrShellCloseDuringModelVerificationCannotStartLater(boolean close) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(models.readyDirectory())
        .thenAnswer(
            invocation -> {
              entered.countDown();
              // A slow resource may finish despite interruption. Admission must still be cancelled.
              while (true)
                try {
                  release.await();
                  break;
                } catch (InterruptedException ignored) {
                }
              return Path.of("unused");
            });
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      if (close) {
        command.closeAutoStart();
        voice.close();
      } else assertThat(command.off()).isZero();
      release.countDown();
      command.autoStart(true, writer::println);
      // Wait for the worker scope to close rather than relying on a sleep.
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(command.autoStartRunning()).isFalse());
      assertThat(captureOpens).hasValue(0);
      assertThat(backendOpens).hasValue(0);
      assertThat(voice.state())
          .isEqualTo(close ? VoiceInputCoordinator.State.CLOSED : VoiceInputCoordinator.State.OFF);
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @ParameterizedTest
  @ValueSource(strings = {"on", "test", "config"})
  void manualOperationSupersedesSlowAutomaticVerification(String operation) throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var checks = new AtomicInteger();
    when(models.readyDirectory())
        .thenAnswer(
            invocation -> {
              if (checks.incrementAndGet() == 1) {
                entered.countDown();
                while (true)
                  try {
                    release.await();
                    break;
                  } catch (InterruptedException ignored) {
                  }
                throw new IOException("old automatic attempt failed late");
              }
              return Path.of("unused");
            });
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      var cli = new CommandLine(command);
      cli.setOut(writer);
      cli.setErr(writer);
      assertThat(
              operation.equals("config")
                  ? cli.execute("config", "--silence-ms", "900")
                  : cli.execute(operation))
          .isZero();
      release.countDown();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(command.autoStartRunning()).isFalse());
      assertThat(voice.state())
          .isEqualTo(
              operation.equals("config")
                  ? VoiceInputCoordinator.State.OFF
                  : VoiceInputCoordinator.State.LISTENING);
      assertThat(backendOpens).hasValue(operation.equals("config") ? 0 : 1);
      assertThat(output.toString()).doesNotContain("old automatic attempt failed late");
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void selectionChangeDuringVerificationDoesNotRetargetStartup() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    when(models.readyDirectory())
        .thenAnswer(
            invocation -> {
              entered.countDown();
              release.await();
              return Path.of("unused");
            });
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      when(shell.isSelected(client, target)).thenReturn(false);
      release.countDown();
      await()
          .atMost(Duration.ofSeconds(3))
          .untilAsserted(() -> assertThat(output.toString()).contains("Project/Session"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0);
      assertThat(captureOpens).hasValue(0);
      verify(delivery, never()).bind(any(), any());
    } finally {
      release.countDown();
    }
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void offOrCloseDuringNativeStartupCancelsItAndCannotReopenMicrophone(boolean close)
      throws Exception {
    var entered = new CountDownLatch(1);
    try (var voice =
        coordinator(
            settings -> {
              entered.countDown();
              Thread.sleep(60000);
              throw new AssertionError();
            })) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      if (close) {
        command.closeAutoStart();
        voice.close();
      } else assertThat(command.off()).isZero();
      state(voice, close ? VoiceInputCoordinator.State.CLOSED : VoiceInputCoordinator.State.OFF);
      assertThat(captureOpens).hasValue(0);
    }
  }
}
