package dev.mikoto2000.rei.voice;

import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DoctorMicrophoneAcquisitionTest {
  @Test void acquiresAndClosesWithoutStartingOrReading() throws Exception {
    Mixer.Info info = new Mixer.Info("fixture", "vendor", "description", "1") {};
    Mixer mixer = mock(Mixer.class);
    TargetDataLine line = mock(TargetDataLine.class);
    when(mixer.isLineSupported(any())).thenReturn(true);
    when(mixer.getLine(any())).thenReturn(line);
    try (var system = mockStatic(AudioSystem.class)) {
      system.when(AudioSystem::getMixerInfo).thenReturn(new Mixer.Info[]{info});
      system.when(() -> AudioSystem.getMixer(info)).thenReturn(mixer);
      new JavaSoundMicrophoneCapture().acquireWithoutRecording(AudioDeviceService.describe(info));
      verify(line).open(any(AudioFormat.class));
      verify(line).close();
      verify(line, never()).start();
      verify(line, never()).read(any(), anyInt(), anyInt());
    }
  }
  @Test void failedOpenStillClosesWithoutStarting() throws Exception {
    Mixer.Info info = new Mixer.Info("fixture", "vendor", "description", "1") {};
    Mixer mixer = mock(Mixer.class);
    TargetDataLine line = mock(TargetDataLine.class);
    when(mixer.isLineSupported(any())).thenReturn(true);
    when(mixer.getLine(any())).thenReturn(line);
    doThrow(new LineUnavailableException("private detail")).when(line).open(any(AudioFormat.class));
    try (var system = mockStatic(AudioSystem.class)) {
      system.when(AudioSystem::getMixerInfo).thenReturn(new Mixer.Info[]{info});
      system.when(() -> AudioSystem.getMixer(info)).thenReturn(mixer);
      assertThrows(LineUnavailableException.class, () -> new JavaSoundMicrophoneCapture().acquireWithoutRecording(AudioDeviceService.describe(info)));
      verify(line).close();
      verify(line, never()).start();
    }
  }
}
