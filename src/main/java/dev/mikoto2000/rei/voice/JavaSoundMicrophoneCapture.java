package dev.mikoto2000.rei.voice;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** 音声による対話に必要なJava音通知マイク取得記録を扱う。 */
public final class JavaSoundMicrophoneCapture implements MicrophoneCaptureService {
  /** 必要な対象を利用できるよう、Java音通知マイク取得記録の利用を開始する。 */
  public FrameSource open(AudioDevice device) throws Exception {
    Mixer mixer = resolveMixer(device);
    AudioFormat format = selectFormat(mixer);
    var line = (TargetDataLine) mixer.getLine(new DataLine.Info(TargetDataLine.class, format));
    try {
      line.open(format);
      line.start();
      var raw = new AudioInputStream(line);
      var pcm = AudioFormatConverter.toPcm16(raw);
      return new FrameSource() {
        /** 終了済み。 */
        final AtomicBoolean closed = new AtomicBoolean();

        /** 必要な内容を参照できるよう、観測単位を読み取る。 */
        public float[] readFrame() throws IOException {
          byte[] bytes = new byte[VoiceSettings.WINDOW * 2];
          int offset = 0, empty = 0;
          while (offset < bytes.length && !closed.get()) {
            int count = pcm.read(bytes, offset, bytes.length - offset);
            if (count < 0) return null;
            if (count == 0) {
              if (++empty >= 3) throw new IOException("Microphone stopped producing frames");
              continue;
            }
            offset += count;
            empty = 0;
          }
          return offset == bytes.length && !closed.get() ? VoicePcm.decode(bytes) : null;
        }

        /** 音声による対話で利用した資源や登録を解放し、利用を終了する。 */
        public void close() {
          // 複数の経路から同じ処理が求められても、状態の切り替えは一度だけ行う。
          if (closed.compareAndSet(false, true)) {
            line.stop();
            line.close();
            try {
              pcm.close();
            } catch (IOException ignored) {
            }
          }
        }
      };
    } catch (Exception | LinkageError e) {
      line.close();
      throw e;
    }
  }

  /** 指定や設定に応じた対象を利用するため、Mixerを確定する。 */
  private Mixer resolveMixer(AudioDevice device) {
    var matches =
        Arrays.stream(AudioSystem.getMixerInfo())
            .filter(info -> AudioDeviceService.describe(info).equals(device))
            .toList();
    if (matches.size() != 1)
      throw new IllegalStateException("Selected microphone disconnected or ambiguous");
    return AudioSystem.getMixer(matches.getFirst());
  }

  /**
   * 音声による対話に関する確保なしRecordingを処理する。
   *
   * <p>Diagnostic acquisition only: no start, stream creation or audio read.
   */
  public void acquireWithoutRecording(AudioDevice device) throws Exception {
    if (Thread.currentThread().isInterrupted())
      throw new java.util.concurrent.CancellationException();
    Mixer mixer = resolveMixer(device);
    AudioFormat format = selectFormat(mixer);
    var line = (TargetDataLine) mixer.getLine(new DataLine.Info(TargetDataLine.class, format));
    try {
      if (Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
      line.open(format);
      if (Thread.currentThread().isInterrupted())
        throw new java.util.concurrent.CancellationException();
    } finally {
      line.close();
    }
  }

  /** 条件に合う対象を利用するため、形式を選択する。 */
  private AudioFormat selectFormat(Mixer mixer) {
    var preferred = AudioFormatConverter.PCM16;
    if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, preferred))) return preferred;
    for (int rate : new int[] {48000, 44100, 32000, 16000})
      for (int channels : new int[] {1, 2}) {
        var candidate = new AudioFormat(rate, 16, channels, true, false);
        if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, candidate))
            && AudioSystem.isConversionSupported(preferred, candidate)) return candidate;
      }
    throw new IllegalStateException("Selected microphone has no supported PCM16 conversion");
  }
}
