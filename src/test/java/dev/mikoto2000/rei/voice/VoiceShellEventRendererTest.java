package dev.mikoto2000.rei.voice;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.ui.shell.ShellEventOutput;
class VoiceShellEventRendererTest {
  @Test void listeningCueAndFailuresUseJLineOutputWithoutRawTranscript() {
    var output=mock(ShellEventOutput.class);
    var renderer=new VoiceShellEventRenderer(output);
    renderer.accept(new VoiceEventPublisher.Event(VoiceEventPublisher.Type.STATE_CHANGED,"LISTENING"));
    renderer.accept(new VoiceEventPublisher.Event(VoiceEventPublisher.Type.MAX_DROPPED,"private ignored detail"));
    verify(output).println("[voice] LISTENING: 受付中です");
    verify(output).println("[voice] 最大発話時間の上限に達した未完了発話を破棄しました");
    verify(output,times(2)).flush();
  }
}