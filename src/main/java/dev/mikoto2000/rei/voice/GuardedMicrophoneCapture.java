package dev.mikoto2000.rei.voice;
import java.util.Objects;
/** Checks the selected endpoint before and after opening, and during capture independently of frames. */
public final class GuardedMicrophoneCapture implements MicrophoneCaptureService {
  private final MicrophoneCaptureService capture;
  private final WindowsMicrophoneMonitor monitor;
  public GuardedMicrophoneCapture(MicrophoneCaptureService capture,WindowsMicrophoneMonitor monitor) {
    this.capture=Objects.requireNonNull(capture);this.monitor=Objects.requireNonNull(monitor);
  }
  public FrameSource open(AudioDevice device) throws Exception {
    var binding=monitor.bind(device);var source=capture.open(device);
    try{monitor.check(binding);}catch(RuntimeException failure){source.close();throw failure;}
    return new FrameSource(){
      public float[] readFrame() throws Exception{return source.readFrame();}
      public void checkHealth() throws Exception{monitor.check(binding);source.checkHealth();}
      public void close(){source.close();}
    };
  }
}
