package dev.mikoto2000.rei.voice;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class WindowsMicrophoneMonitorTest {
  final AudioDevice device=new AudioDevice("fingerprint","DRY (VT-4)","capture","vendor","1");
  WindowsAudioEndpoints.Endpoint endpoint(String id,String name,int state){return new WindowsAudioEndpoints.Endpoint(id,name,state);}
  @Test void onlyExactUniqueActiveNameCanBindAndNoDefaultFallbackExists() {
    var snapshot=new AtomicReference<>(List.of(endpoint("other","Other microphone",1)));
    var monitor=new WindowsMicrophoneMonitor(snapshot::get);
    assertThatThrownBy(()->monitor.bind(device)).isInstanceOf(IllegalStateException.class);
    snapshot.set(List.of(endpoint("prefix","DRY (VT-4) extra",1)));
    assertThatThrownBy(()->monitor.bind(device)).isInstanceOf(IllegalStateException.class);
    snapshot.set(List.of(endpoint("dry","DRY (VT-4)",1)));
    var binding=monitor.bind(device);assertThat(binding.endpointId()).isEqualTo("dry");assertThatCode(()->monitor.check(binding)).doesNotThrowAnyException();
  }
  @Test void sameNameActiveEndpointsAreAmbiguousEvenWithDifferentIds() {
    var monitor=new WindowsMicrophoneMonitor(()->List.of(endpoint("one",device.name(),1),endpoint("two",device.name(),1)));
    assertThatThrownBy(()->monitor.bind(device)).isInstanceOf(IllegalStateException.class);
  }
  @Test void disconnectedDisabledOrReplacedEndpointStopsInsteadOfFollowingSameName() {
    var snapshot=new AtomicReference<>(List.of(endpoint("original",device.name(),1)));var monitor=new WindowsMicrophoneMonitor(snapshot::get);var binding=monitor.bind(device);
    for(int state:List.of(2,4,8)) {
      snapshot.set(List.of(endpoint("original",device.name(),state)));assertThatThrownBy(()->monitor.check(binding)).isInstanceOf(IllegalStateException.class);
    }
    snapshot.set(List.of(endpoint("replacement",device.name(),1)));
    assertThatThrownBy(()->monitor.check(binding)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(()->monitor.bind(device)).isInstanceOf(IllegalStateException.class);
    monitor.reselect(device.id());assertThat(monitor.bind(device).endpointId()).isEqualTo("replacement");
  }
  @Test void renamedEndpointIsNotSilentlyMappedByPartialMatch() {
    var snapshot=new AtomicReference<>(List.of(endpoint("original",device.name(),1)));var monitor=new WindowsMicrophoneMonitor(snapshot::get);var binding=monitor.bind(device);
    snapshot.set(List.of(endpoint("original","Renamed microphone",1)));assertThatThrownBy(()->monitor.check(binding)).isInstanceOf(IllegalStateException.class);
  }
}