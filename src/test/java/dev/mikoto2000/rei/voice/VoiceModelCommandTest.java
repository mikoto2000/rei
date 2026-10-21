package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

/** 音声モデルコマンドの結果と状態の契約を検証するテスト。 */
class VoiceModelCommandTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使うデータ。 */
  final byte[] data = "fixture".getBytes();

  /** {@code manifest} の条件を再現するための補助情報を返す。 */
  VoiceModelManifest manifest() throws Exception {
    return new VoiceModelManifest(
        "fixed-approved",
        List.of(
            new VoiceModelManifest.Asset(
                "model",
                URI.create("https://example.org/fixed"),
                data.length,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)),
                "MIT")));
  }

  /** {@code voice} の条件を再現するための補助情報を返す。 */
  VoiceInputCoordinator voice() {
    var voice = mock(VoiceInputCoordinator.class);
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    when(voice.start(any(), any(), any()))
        .thenReturn(timeout -> VoiceInputCoordinator.State.LISTENING);
    return voice;
  }

  /** {@code devices} の条件を再現するための補助情報を返す。 */
  AudioDeviceService devices() {
    var devices =
        new AudioDeviceService(
            () -> List.of(new AudioDevice("dry", "DRY (VT-4)", "input", "vendor", "1")));
    devices.select("dry");
    return devices;
  }

  /** {@code command} の条件を再現するための補助情報を返す。 */
  CommandLine command(
      VoiceInputCoordinator voice,
      ShellConversationService shell,
      VoiceModelManager manager,
      StringWriter output) {
    var command =
        new CommandLine(new VoiceCommand(voice, devices(), new VoiceProperties(), shell, manager));
    command.setOut(new PrintWriter(output, true));
    command.setErr(new PrintWriter(output, true));
    return command;
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void missingBundleOnDisclosesSourceLicenseSizeAndDoesNotCaptureOrDownload() throws Exception {
    var calls = new AtomicInteger();
    var voice = voice();
    var shell = mock(ShellConversationService.class);
    var output = new StringWriter();
    try (var manager =
        new VoiceModelManager(
            root, manifest(), (a, p, c, progress) -> calls.incrementAndGet(), s -> {})) {
      var command = command(voice, shell, manager, output);
      assertThat(command.execute("on")).isEqualTo(2);
      assertThat(calls).hasValue(0);
      // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
      verifyNoInteractions(shell);
      verify(voice, never()).start(any(), any(), any());
      assertThat(output.toString())
          .contains("fixed-approved", "https://example.org/fixed", "MIT", "7 bytes", "--approve");
    }
  }

  /** 音声による対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void approvalDownloadsAsynchronouslyThenOnUsesSameConversationRoute() throws Exception {
    var voice = voice();
    var shell = mock(ShellConversationService.class);
    var output = new StringWriter();
    var m = manifest();
    var target =
        new dev.mikoto2000.rei.application.input.ConversationTarget(
            new dev.mikoto2000.rei.core.project.ProjectContext(
                UUID.randomUUID().toString(), "test", root),
            "session");
    when(shell.captureTarget()).thenReturn(target);
    try (var manager =
        new VoiceModelManager(
            root, m, (a, p, c, progress) -> java.nio.file.Files.write(p, data), s -> {})) {
      var command = command(voice, shell, manager, output);
      assertThat(command.execute("models", "install")).isEqualTo(2);
      assertThat(command.execute("models", "install", "--approve", m.id())).isZero();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (manager.busy() && System.nanoTime() < deadline) Thread.sleep(5);
      assertThat(manager.status().state()).isEqualTo(VoiceModelManager.State.READY);
      assertThat(command.execute("on")).isZero();
      verify(voice).start(eq(target), any(), eq(VoiceSettings.defaults()));
    }
  }

  /** 音声による対話の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void statusAndCancelRemainAvailableWhileDownloadIsBlocked() throws Exception {
    var entered = new CountDownLatch(1);
    var m = manifest();
    var output = new StringWriter();
    try (var manager =
        new VoiceModelManager(
            root,
            m,
            (a, p, c, progress) -> {
              entered.countDown();
              try {
                Thread.sleep(10000);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
              }
            },
            s -> {})) {
      var command = command(voice(), mock(ShellConversationService.class), manager, output);
      assertThat(command.execute("models", "install", "--approve", m.id())).isZero();
      assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(command.execute("models", "status")).isZero();
      assertThat(output.toString()).contains("DOWNLOADING");
      assertThat(command.execute("models", "cancel")).isZero();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (manager.busy() && System.nanoTime() < deadline) Thread.sleep(5);
      assertThat(manager.status().state()).isEqualTo(VoiceModelManager.State.CANCELLED);
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void explicitVerifyChecksLocalHashesWithoutDownloadingAndRejectsCorruption() throws Exception {
    var calls = new AtomicInteger();
    var m = manifest();
    var output = new StringWriter();
    try (var manager =
        new VoiceModelManager(
            root,
            m,
            (a, p, c, progress) -> {
              calls.incrementAndGet();
              java.nio.file.Files.write(p, data);
            },
            s -> {})) {
      var command = command(voice(), mock(ShellConversationService.class), manager, output);
      assertThat(command.execute("models", "verify")).isEqualTo(2);
      assertThat(calls).hasValue(0);
      manager.install(m.id());
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
      while (manager.busy() && System.nanoTime() < deadline) Thread.sleep(5);
      assertThat(command.execute("models", "verify")).isZero();
      assertThat(calls).hasValue(1);
      java.nio.file.Files.writeString(manager.readyDirectory().resolve("model"), "corrupt");
      assertThat(command.execute("models", "verify")).isEqualTo(2);
      assertThat(calls).hasValue(1);
    }
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void infoDisclosesFp32SidecarAndLargeTotalWithoutDownloading() {
    var output = new StringWriter();
    var calls = new AtomicInteger();
    try (var manager =
        new VoiceModelManager(
            root,
            VoiceModelManifest.pinned(),
            (a, p, c, progress) -> calls.incrementAndGet(),
            status -> {})) {
      assertThat(
              command(voice(), mock(ShellConversationService.class), manager, output)
                  .execute("models", "info"))
          .isZero();
      assertThat(output.toString())
          .contains(
              "large-v3-turbo multilingual FP32",
              "turbo-encoder.weights",
              "2600325120 bytes",
              "3247195692 bytes");
      assertThat(calls).hasValue(0);
    }
  }
}
