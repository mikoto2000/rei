package dev.mikoto2000.rei.voice;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class AudioDeviceServiceTest {
  final AudioDevice dry = new AudioDevice("dry", "DRY (VT-4)", "input", "vendor", "1");
  @Test void requiresExplicitSelectionAndRejectsDisconnectedOrAmbiguousIdentity() {
    var service = new AudioDeviceService(() -> List.of(dry));
    assertThatThrownBy(() -> service.selected()).isInstanceOf(IllegalStateException.class);
    service.select("dry"); assertThat(service.selected()).isEqualTo(dry);
    assertThatThrownBy(() -> service.select("missing")).isInstanceOf(IllegalArgumentException.class);
    var duplicate = new AudioDeviceService(() -> List.of(dry, dry));
    assertThatThrownBy(() -> duplicate.select("dry")).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void disconnectedSelectionNeverFallsBack() {
    var devices = new java.util.concurrent.atomic.AtomicReference<>(List.of(dry));
    var service = new AudioDeviceService(devices::get);
    service.select("dry"); devices.set(List.of(new AudioDevice("other","other","input","vendor","1")));
    assertThatThrownBy(() -> service.selected()).isInstanceOf(IllegalStateException.class);
  }
}