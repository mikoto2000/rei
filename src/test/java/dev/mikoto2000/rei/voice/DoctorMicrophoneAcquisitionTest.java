package dev.mikoto2000.rei.voice;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import org.junit.jupiter.api.Test;

/** 環境診断マイク情報の取得の結果と状態の契約を検証するテスト。 */
class DoctorMicrophoneAcquisitionTest {
  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void acquiresAndClosesWithoutStartingOrReading() throws Exception {
    Mixer.Info info = new Mixer.Info("fixture", "vendor", "description", "1") {};
    Mixer mixer = mock(Mixer.class);
    TargetDataLine line = mock(TargetDataLine.class);
    when(mixer.isLineSupported(any())).thenReturn(true);
    when(mixer.getLine(any())).thenReturn(line);
    try (var system = mockStatic(AudioSystem.class)) {
      system.when(AudioSystem::getMixerInfo).thenReturn(new Mixer.Info[] {info});
      system.when(() -> AudioSystem.getMixer(info)).thenReturn(mixer);
      new JavaSoundMicrophoneCapture().acquireWithoutRecording(AudioDeviceService.describe(info));
      verify(line).open(any(AudioFormat.class));
      verify(line).close();
      verify(line, never()).start();
      verify(line, never()).read(any(), anyInt(), anyInt());
    }
  }

  /** 音声による対話で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failedOpenStillClosesWithoutStarting() throws Exception {
    Mixer.Info info = new Mixer.Info("fixture", "vendor", "description", "1") {};
    Mixer mixer = mock(Mixer.class);
    TargetDataLine line = mock(TargetDataLine.class);
    when(mixer.isLineSupported(any())).thenReturn(true);
    when(mixer.getLine(any())).thenReturn(line);
    doThrow(new LineUnavailableException("private detail")).when(line).open(any(AudioFormat.class));
    try (var system = mockStatic(AudioSystem.class)) {
      system.when(AudioSystem::getMixerInfo).thenReturn(new Mixer.Info[] {info});
      system.when(() -> AudioSystem.getMixer(info)).thenReturn(mixer);
      assertThrows(
          LineUnavailableException.class,
          () ->
              new JavaSoundMicrophoneCapture()
                  .acquireWithoutRecording(AudioDeviceService.describe(info)));
      verify(line).close();
      verify(line, never()).start();
    }
  }
}
