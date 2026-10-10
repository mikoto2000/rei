package dev.mikoto2000.rei.voice;

import java.io.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import picocli.CommandLine;
import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

class VoiceAutoStartTest {
  final VoiceProperties properties = new VoiceProperties();
  final VoiceModelManager models = mock(VoiceModelManager.class);
  final ShellConversationService shell = mock(ShellConversationService.class);
  final VoiceDeliveryService delivery = mock(VoiceDeliveryService.class);
  final ProjectClient client = mock(ProjectClient.class);
  final ConversationTarget target = new ConversationTarget(
      new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");
  final AudioDevice device = new AudioDevice("test-device", "test", "input", "vendor", "1");
  final AudioDeviceService devices = new AudioDeviceService(() -> List.of(device));
  final StringWriter output = new StringWriter();
  final PrintWriter writer = new PrintWriter(output, true);
  final AtomicInteger backendOpens = new AtomicInteger();
  final AtomicInteger captureOpens = new AtomicInteger();

  VoiceAutoStartTest() throws Exception {
    properties.setAutoStart(true);
    devices.select(device.id());
    when(models.readyDirectory()).thenReturn(Path.of("unused-fake-models"));
    when(shell.captureClient()).thenReturn(client);
    when(shell.captureTarget()).thenReturn(target);
    when(shell.isSelected(client, target)).thenReturn(true);
    when(client.open()).thenAnswer(invocation -> ProjectClientScope.open(client));
  }

  VoiceInputCoordinator coordinator() {
    return coordinator(settings -> {
      backendOpens.incrementAndGet();
      return new VoiceBackend(new VoiceActivityDetector() {
        public float probability(float[] frame) { return 0; }
        public void close() { }
      }, new SpeechRecognizer() {
        public String recognize(SpeechSegment segment) { return "unused"; }
        public void close() { }
      });
    });
  }

  VoiceInputCoordinator coordinator(VoiceBackendFactory backend) {
    return new VoiceInputCoordinator(selected -> {
      captureOpens.incrementAndGet();
      return new MicrophoneCaptureService.FrameSource() {
        public float[] readFrame() throws InterruptedException { Thread.sleep(60000); return null; }
        public void close() { }
      };
    }, backend, input -> { }, new VoiceEventPublisher(), Clock.systemUTC());
  }

  VoiceCommand command(VoiceInputCoordinator voice) {
    var command = new VoiceCommand(voice, devices, properties, shell, models, delivery);
    var cli = new CommandLine(command);
    cli.setOut(writer); cli.setErr(writer);
    return command;
  }

  void state(VoiceInputCoordinator voice, VoiceInputCoordinator.State expected) {
    await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(voice.state()).isEqualTo(expected));
  }

  @Test void configurationIsOptInAndBindsBothBooleanValues() {
    assertThat(new VoiceProperties().isAutoStart()).isFalse();
    for (boolean enabled : new boolean[] {false, true}) {
      var binder = new Binder(new MapConfigurationPropertySource(Map.of("rei.voice.auto-start", enabled)));
      assertThat(binder.bind("rei.voice", Bindable.of(VoiceProperties.class)).get().isAutoStart()).isEqualTo(enabled);
    }
  }

  @Test void constructionAndDisabledStartupDoNotTouchModelsOrMicrophone() {
    try (var voice = coordinator()) {
      var command = command(voice);
      verifyNoInteractions(models, shell, delivery);
      properties.setAutoStart(false);
      command.autoStart(true, writer::println);
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      verifyNoInteractions(models, shell, delivery);
      assertThat(backendOpens).hasValue(0); assertThat(captureOpens).hasValue(0);
    }
  }

  @Test void noninteractiveShellNeverStartsEvenWhenEnabled() {
    try (var voice = coordinator()) {
      command(voice).autoStart(false, writer::println);
      verifyNoInteractions(models, shell, delivery);
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
    }
  }

  @Test void enabledStartupIsOneShotAndManualOffAndOnStillWork() {
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      state(voice, VoiceInputCoordinator.State.LISTENING);
      command.autoStart(true, writer::println);
      verify(delivery).bind(client, target);
      verify(client).open();
      assertThat(backendOpens).hasValue(1); assertThat(captureOpens).hasValue(1);
      assertThat(command.off()).isZero();
      state(voice, VoiceInputCoordinator.State.OFF);
      command.autoStart(true, writer::println);
      assertThat(command.on()).isZero();
      assertThat(backendOpens).hasValue(2); assertThat(captureOpens).hasValue(2);
      verify(models, never()).install(any());
    }
  }

  @Test void missingModelsLeaveVoiceOffAndDoNotDownload() throws Exception {
    when(models.readyDirectory()).thenThrow(new IOException("missing"));
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.toString()).contains("モデル一式"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0); assertThat(captureOpens).hasValue(0);
      command.autoStart(true, writer::println);
      verify(models, never()).install(any());
      verify(models).readyDirectory();
    }
  }

  @Test void modelDownloadInProgressDoesNotTriggerRecordingOrAnotherDownload() {
    when(models.busy()).thenReturn(true);
    try (var voice = coordinator()) {
      command(voice).autoStart(true, writer::println);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.toString()).contains("モデル取得中"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0); assertThat(captureOpens).hasValue(0);
      verify(models, never()).install(any());
    }
  }

  @Test void manualOffBeforeShellReadySuppressesAutomaticAttempt() {
    try (var voice = coordinator()) {
      var command = command(voice);
      assertThat(command.off()).isZero();
      command.autoStart(true, writer::println);
      verifyNoInteractions(models, shell);
      assertThat(backendOpens).hasValue(0); assertThat(captureOpens).hasValue(0);
    }
  }

  @Test void unselectedMicrophoneIsExplainedWithoutBackendStartup() {
    devices.invalidateSelection();
    try (var voice = coordinator()) {
      command(voice).autoStart(true, writer::println);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.toString()).contains("Select a microphone"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0); assertThat(captureOpens).hasValue(0);
    }
  }

  @Test void nativeStartupFailureReturnsToOffWithoutStoppingTheShell() {
    try (var voice = coordinator(settings -> { throw new UnsatisfiedLinkError("fake native failure"); })) {
      command(voice).autoStart(true, writer::println);
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.toString()).contains("自動開始できませんでした"));
      state(voice, VoiceInputCoordinator.State.OFF);
      assertThat(captureOpens).hasValue(0);
    }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void offOrShellCloseDuringModelVerificationCannotStartLater(boolean close) throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    when(models.readyDirectory()).thenAnswer(invocation -> {
      entered.countDown();
      // A slow resource may finish despite interruption. Admission must still be cancelled.
      while (true) try { release.await(); break; } catch (InterruptedException ignored) { }
      return Path.of("unused");
    });
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      if (close) { command.closeAutoStart(); voice.close(); } else assertThat(command.off()).isZero();
      release.countDown();
      command.autoStart(true, writer::println);
      // Wait for the worker scope to close rather than relying on a sleep.
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(command.autoStartRunning()).isFalse());
      assertThat(captureOpens).hasValue(0); assertThat(backendOpens).hasValue(0);
      assertThat(voice.state()).isEqualTo(close ? VoiceInputCoordinator.State.CLOSED : VoiceInputCoordinator.State.OFF);
    } finally { release.countDown(); }
  }

  @ParameterizedTest @ValueSource(strings = {"on", "test", "config"})
  void manualOperationSupersedesSlowAutomaticVerification(String operation) throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var checks = new AtomicInteger();
    when(models.readyDirectory()).thenAnswer(invocation -> {
      if (checks.incrementAndGet() == 1) {
        entered.countDown();
        while (true) try { release.await(); break; } catch (InterruptedException ignored) { }
        throw new IOException("old automatic attempt failed late");
      }
      return Path.of("unused");
    });
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      var cli = new CommandLine(command); cli.setOut(writer); cli.setErr(writer);
      assertThat(operation.equals("config") ? cli.execute("config", "--silence-ms", "900") : cli.execute(operation)).isZero();
      release.countDown();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(command.autoStartRunning()).isFalse());
      assertThat(voice.state()).isEqualTo(operation.equals("config") ? VoiceInputCoordinator.State.OFF : VoiceInputCoordinator.State.LISTENING);
      assertThat(backendOpens).hasValue(operation.equals("config") ? 0 : 1);
      assertThat(output.toString()).doesNotContain("old automatic attempt failed late");
    } finally { release.countDown(); }
  }

  @Test void selectionChangeDuringVerificationDoesNotRetargetStartup() throws Exception {
    var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
    when(models.readyDirectory()).thenAnswer(invocation -> { entered.countDown(); release.await(); return Path.of("unused"); });
    try (var voice = coordinator()) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      when(shell.isSelected(client, target)).thenReturn(false);
      release.countDown();
      await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(output.toString()).contains("Project/Session"));
      assertThat(voice.state()).isEqualTo(VoiceInputCoordinator.State.OFF);
      assertThat(backendOpens).hasValue(0); assertThat(captureOpens).hasValue(0);
      verify(delivery, never()).bind(any(), any());
    } finally { release.countDown(); }
  }

  @ParameterizedTest @ValueSource(booleans = {false, true})
  void offOrCloseDuringNativeStartupCancelsItAndCannotReopenMicrophone(boolean close) throws Exception {
    var entered = new CountDownLatch(1);
    try (var voice = coordinator(settings -> { entered.countDown(); Thread.sleep(60000); throw new AssertionError(); })) {
      var command = command(voice);
      command.autoStart(true, writer::println);
      assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
      if (close) { command.closeAutoStart(); voice.close(); } else assertThat(command.off()).isZero();
      state(voice, close ? VoiceInputCoordinator.State.CLOSED : VoiceInputCoordinator.State.OFF);
      assertThat(captureOpens).hasValue(0);
    }
  }
}
