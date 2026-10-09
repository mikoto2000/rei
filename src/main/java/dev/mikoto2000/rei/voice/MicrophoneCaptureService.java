package dev.mikoto2000.rei.voice;
@FunctionalInterface
public interface MicrophoneCaptureService {
  FrameSource open(AudioDevice device) throws Exception;
  interface FrameSource extends AutoCloseable {
    float[] readFrame() throws Exception;
    void close();
  }
}