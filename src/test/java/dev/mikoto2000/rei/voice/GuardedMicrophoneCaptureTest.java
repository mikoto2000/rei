package dev.mikoto2000.rei.voice;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class GuardedMicrophoneCaptureTest {
  @Test void missingEndpointNeverOpensJavaSoundAndDisconnectedSourceFailsHealth() throws Exception {
    var device=new AudioDevice("dry","DRY (VT-4)","input","vendor","1");
    var endpoints=new AtomicReference<List<WindowsAudioEndpoints.Endpoint>>(List.of());
    var monitor=new WindowsMicrophoneMonitor(endpoints::get);var opens=new AtomicInteger();var closes=new AtomicInteger();
    MicrophoneCaptureService delegate=d->{opens.incrementAndGet();return new MicrophoneCaptureService.FrameSource(){
      public float[] readFrame(){return new float[512];}public void close(){closes.incrementAndGet();}
    };};
    var capture=new GuardedMicrophoneCapture(delegate,monitor);
    assertThatThrownBy(()->capture.open(device)).isInstanceOf(IllegalStateException.class);assertThat(opens.get()).isZero();
    endpoints.set(List.of(new WindowsAudioEndpoints.Endpoint("endpoint",device.name(),1)));
    var source=capture.open(device);source.checkHealth();assertThat(source.readFrame()).hasSize(512);
    endpoints.set(List.of(new WindowsAudioEndpoints.Endpoint("endpoint",device.name(),8)));
    assertThatThrownBy(source::checkHealth).isInstanceOf(IllegalStateException.class);source.close();assertThat(closes.get()).isEqualTo(1);
  }
  @Test void endpointChangeWhileJavaSoundOpensClosesTheJustOpenedSource() {
    var device=new AudioDevice("dry","DRY (VT-4)","input","vendor","1");
    var endpoints=new AtomicReference<>(List.of(new WindowsAudioEndpoints.Endpoint("endpoint",device.name(),1)));
    var monitor=new WindowsMicrophoneMonitor(endpoints::get);var closes=new AtomicInteger();
    MicrophoneCaptureService delegate=d->{endpoints.set(List.of());return new MicrophoneCaptureService.FrameSource(){
      public float[] readFrame(){return null;}public void close(){closes.incrementAndGet();}
    };};
    assertThatThrownBy(()->new GuardedMicrophoneCapture(delegate,monitor).open(device)).isInstanceOf(IllegalStateException.class);
    assertThat(closes.get()).isEqualTo(1);
  }
}
