package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.ShellConversationService;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 音声推定選択可能な設定の結果と状態の契約を検証するテスト。 */
class VoiceInferenceOptionsTest {
  /** 音声による対話で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void cpuBudgetAndPaddingAreBounded() {
    assertThat(VoiceInferenceOptions.defaults()).isEqualTo(new VoiceInferenceOptions(4, 1000));
    for (int threads : new int[] {0, 5})
      assertThatThrownBy(() -> new VoiceInferenceOptions(threads, 500))
          .isInstanceOf(IllegalArgumentException.class);
    for (int padding : new int[] {249, 2001})
      assertThatThrownBy(() -> new VoiceInferenceOptions(1, padding))
          .isInstanceOf(IllegalArgumentException.class);
  }

  /** {@code command} の条件を再現するための補助情報を返す。 */
  CommandLine command(VoiceInputCoordinator voice, VoiceProperties properties) {
    var cli =
        new CommandLine(
            new VoiceCommand(
                voice,
                new AudioDeviceService(() -> java.util.List.of()),
                properties,
                mock(ShellConversationService.class)));
    cli.setOut(new PrintWriter(new StringWriter()));
    cli.setErr(new PrintWriter(new StringWriter()));
    return cli;
  }

  /** 音声による対話で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void inferenceChangesAreOffOnlyAndVadEditsPreserveThem() {
    var voice = mock(VoiceInputCoordinator.class);
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    var properties = new VoiceProperties();
    var cli = command(voice, properties);
    assertThat(cli.execute("config", "--asr-threads", "2", "--asr-tail-frames", "1000")).isZero();
    assertThat(properties.inference()).isEqualTo(new VoiceInferenceOptions(2, 1000));
    assertThat(cli.execute("config", "--silence-ms", "1200")).isZero();
    assertThat(properties.inference()).isEqualTo(new VoiceInferenceOptions(2, 1000));
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.STARTING);
    assertThat(cli.execute("config", "--asr-threads", "4")).isEqualTo(2);
    assertThat(properties.inference()).isEqualTo(new VoiceInferenceOptions(2, 1000));
    verify(voice, never()).start(any(), any(), any());
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void invalidInferenceCannotPartiallyChangeVadOrConfirmation() {
    var voice = mock(VoiceInputCoordinator.class);
    when(voice.state()).thenReturn(VoiceInputCoordinator.State.OFF);
    var properties = new VoiceProperties();
    var cli = command(voice, properties);
    assertThat(
            cli.execute(
                "config", "--asr-threads", "5", "--silence-ms", "600", "--confirmation", "true"))
        .isEqualTo(2);
    assertThat(properties.inference()).isEqualTo(VoiceInferenceOptions.defaults());
    assertThat(properties.settings()).isEqualTo(VoiceSettings.defaults());
    assertThat(properties.isConfirmation()).isFalse();
  }

  /** 選択中無音について、1500ミリ秒推論一時停止を保持することを確認する。 */
  @Test
  void selectedSilencePreservesA1500msThinkingPauseAndFinalizesOnce() {
    var assembler = new SpeechSegmentAssembler(VoiceSettings.defaults(), Clock.systemUTC());
    float[] frame = new float[160];
    for (int i = 0; i < 50; i++) assembler.accept(frame, 1);
    for (int i = 0; i < 150; i++) assertThat(assembler.accept(frame, 0).segment()).isNull();
    for (int i = 0; i < 50; i++) assembler.accept(frame, 1);
    for (int i = 0; i < 179; i++) assertThat(assembler.accept(frame, 0).segment()).isNull();
    assertThat(assembler.accept(frame, 0).segment().samples()).hasSize(2700 * 16);
    for (int i = 0; i < 200; i++) assertThat(assembler.accept(frame, 0).segment()).isNull();
  }
}
