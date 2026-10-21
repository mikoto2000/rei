package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.input.ConversationTarget;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.project.ProjectContext;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 音声レビューコマンドの結果と状態の契約を検証するテスト。 */
class VoiceReviewCommandTest {
  /** 検証に使う音声。 */
  final VoiceInputCoordinator voice = mock(VoiceInputCoordinator.class);

  /** 検証に使うシェル。 */
  final ShellConversationService shell = mock(ShellConversationService.class);

  /** 検証に使う配信。 */
  final VoiceDeliveryService delivery = mock(VoiceDeliveryService.class);

  /** 検証に使うこの機能の動作設定。 */
  final VoiceProperties properties = new VoiceProperties();

  /** 検証に使うdevices。 */
  final AudioDeviceService devices =
      new AudioDeviceService(
          () -> List.of(new AudioDevice("dry", "DRY (VT-4)", "input", "vendor", "1")));

  /** {@code command} の条件を再現するための補助情報を返す。 */
  CommandLine command() {
    var command =
        new CommandLine(new VoiceCommand(voice, devices, properties, shell, null, delivery));
    command.setOut(new PrintWriter(new StringWriter()));
    command.setErr(new PrintWriter(new StringWriter()));
    return command;
  }

  /** 停止済み取得記録を必要とすることを確認する。 */
  @Test
  void confirmationChangeValidatesSettingsAtomicallyAndRequiresStoppedCapture() {
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    var command = command();
    assertThat(command.execute("config", "--confirmation", "true", "--silence-ms", "800")).isZero();
    assertThat(properties.isConfirmation()).isTrue();
    verify(delivery).setConfirmation(true);
    assertThat(command.execute("config", "--confirmation", "false", "--max-speech-ms", "26000"))
        .isEqualTo(2);
    assertThat(properties.isConfirmation()).isTrue();
    verify(delivery, never()).setConfirmation(false);
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.LISTENING);
    assertThat(command.execute("config", "--confirmation", "false")).isEqualTo(2);
    assertThat(properties.isConfirmation()).isTrue();
  }

  /** 音声による対話の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void explicitConfirmationAndCancellationUseRecognitionIdentityRatherThanToolApproval() {
    var command = command();
    var id = UUID.randomUUID();
    assertThat(command.execute("confirm", id.toString(), "--text", "音声入力")).isZero();
    verify(delivery).confirm(id, "音声入力");
    when(delivery.cancel(id)).thenReturn(true);
    assertThat(command.execute("pending", "cancel", id.toString())).isZero();
    verify(delivery).cancel(id);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(shell);
    assertThat(command.execute("off")).isZero();
    verify(delivery).clear();
  }

  /** 音声による対話で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void startingCaptureBindsCurrentClientAndConfirmationBeforeOpeningMicrophone() {
    devices.select("dry");
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    when(voice.start(any(), any(), any()))
        .thenReturn(timeout -> VoiceInputCoordinator.State.LISTENING);
    var target =
        new ConversationTarget(
            new ProjectContext(UUID.randomUUID().toString(), "test", Path.of(".")), "session");
    when(shell.captureTarget()).thenReturn(target);
    properties.setConfirmation(true);
    assertThat(command().execute("on")).isZero();
    var order = inOrder(delivery, voice);
    order.verify(voice, times(2)).state();
    order.verify(delivery).setConfirmation(true);
    order.verify(delivery).bind(null, target);
    order.verify(voice).start(target, devices.selected(), properties.settings());
  }
}
