package dev.mikoto2000.rei.voice;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
import org.junit.jupiter.api.Test;

/** 音声シェルイベント表示への変換の結果と状態の契約を検証するテスト。 */
class VoiceShellEventRendererTest {
  @Test
  void privacyFailureExplainsLifeSettingsInsteadOfNativeInitialization() {
    var output = mock(ShellEventOutput.class);
    new VoiceShellEventRenderer(output)
        .accept(
            new VoiceEventPublisher.Event(
                VoiceEventPublisher.Type.PRIVACY_BLOCKED, "voice stopped"));
    verify(output)
        .println(
            "[voice] 勤務・休息時間などの生活制約により音声入力を停止しました。/secretary life-status と /secretary life-settings を確認してください");
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void listeningCueAndFailuresUseJLineOutputWithoutRawTranscript() {
    var output = mock(ShellEventOutput.class);
    var renderer = new VoiceShellEventRenderer(output);
    renderer.accept(
        new VoiceEventPublisher.Event(VoiceEventPublisher.Type.STATE_CHANGED, "LISTENING"));
    renderer.accept(
        new VoiceEventPublisher.Event(
            VoiceEventPublisher.Type.MAX_DROPPED, "private ignored detail"));
    verify(output).println("[voice] LISTENING: 受付中です");
    verify(output).println("[voice] 最大発話時間の上限に達した未完了発話を破棄しました");
    verify(output, times(2)).flush();
  }
}
