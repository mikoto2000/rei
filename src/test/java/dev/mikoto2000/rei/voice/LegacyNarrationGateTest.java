package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationProperties;
import dev.mikoto2000.rei.ui.shell.sound.SoundNotificationService;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LegacyNarrationGateTest {
  @Test
  void configuredNotificationSuppressesVoiceBeforeLaunchingAndIncludesTail() throws Exception {
    var gate = new VoiceAudioGate(System::nanoTime);
    var properties = new SoundNotificationProperties();
    properties.setEnabled(true);
    properties.setCommand(List.of("configured", "{{MESSAGE}}"));
    var process = mock(Process.class);
    when(process.waitFor(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
    var builder = mock(ProcessBuilder.class);
    when(builder.start()).thenReturn(process);
    var sound =
        new SoundNotificationService(properties) {
          @Override
          protected ProcessBuilder createProcessBuilder(List<String> command) {
            assertThat(gate.suppressed()).isTrue();
            return builder;
          }
        };
    sound.setVoiceAudioGate(gate);
    sound.notify("hello");
    assertThat(gate.suppressed()).isTrue();
  }
}
