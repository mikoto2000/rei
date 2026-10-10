package dev.mikoto2000.rei.voice;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.*;
public final class JavaSoundMicrophoneCapture implements MicrophoneCaptureService {
  public FrameSource open(AudioDevice device) throws Exception {
    Mixer mixer = resolveMixer(device);
    AudioFormat format = selectFormat(mixer);
    var line = (TargetDataLine)mixer.getLine(new DataLine.Info(TargetDataLine.class,format));
    try {
      line.open(format); line.start();
      var raw = new AudioInputStream(line);
      var pcm = AudioFormatConverter.toPcm16(raw);
      return new FrameSource() {
        final AtomicBoolean closed = new AtomicBoolean();
        public float[] readFrame() throws IOException {
          byte[] bytes = new byte[VoiceSettings.WINDOW*2]; int offset=0, empty=0;
          while (offset<bytes.length && !closed.get()) {
            int count = pcm.read(bytes,offset,bytes.length-offset);
            if (count<0) return null;
            if (count==0) { if (++empty>=3) throw new IOException("Microphone stopped producing frames"); continue; }
            offset+=count; empty=0;
          }
          return offset==bytes.length && !closed.get() ? VoicePcm.decode(bytes) : null;
        }
        public void close() {
          if (closed.compareAndSet(false,true)) {
            line.stop(); line.close();
            try { pcm.close(); } catch (IOException ignored) { }
          }
        }
      };
    } catch (Exception | LinkageError e) { line.close(); throw e; }
  }
  private Mixer resolveMixer(AudioDevice device) {
    var matches = Arrays.stream(AudioSystem.getMixerInfo())
      .filter(info -> AudioDeviceService.describe(info).equals(device)).toList();
    if (matches.size()!=1) throw new IllegalStateException("Selected microphone disconnected or ambiguous");
    return AudioSystem.getMixer(matches.getFirst());
  }
  /** Diagnostic acquisition only: no start, stream creation or audio read. */
  public void acquireWithoutRecording(AudioDevice device) throws Exception {
    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    Mixer mixer = resolveMixer(device);
    AudioFormat format = selectFormat(mixer);
    var line = (TargetDataLine)mixer.getLine(new DataLine.Info(TargetDataLine.class,format));
    try {
      if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
      line.open(format);
      if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
    } finally { line.close(); }
  }
  private AudioFormat selectFormat(Mixer mixer) {
    var preferred = AudioFormatConverter.PCM16;
    if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class,preferred))) return preferred;
    for (int rate : new int[]{48000,44100,32000,16000}) for (int channels : new int[]{1,2}) {
      var candidate = new AudioFormat(rate,16,channels,true,false);
      if (mixer.isLineSupported(new DataLine.Info(TargetDataLine.class,candidate))
          && AudioSystem.isConversionSupported(preferred,candidate)) return candidate;
    }
    throw new IllegalStateException("Selected microphone has no supported PCM16 conversion");
  }
}
