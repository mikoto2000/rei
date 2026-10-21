package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 判定期間マイク排他制御の結果と状態の契約を検証するテスト。 */
class WindowsMicrophoneMonitorTest {
  /** 検証に使うdevice。 */
  final AudioDevice device = new AudioDevice("fingerprint", "DRY (VT-4)", "capture", "vendor", "1");

  /** {@code endpoint} の条件を再現するための補助情報を返す。 */
  WindowsAudioEndpoints.Endpoint endpoint(String id, String name, int state) {
    return new WindowsAudioEndpoints.Endpoint(id, name, state);
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void onlyExactUniqueActiveNameCanBindAndNoDefaultFallbackExists() {
    var snapshot = new AtomicReference<>(List.of(endpoint("other", "Other microphone", 1)));
    var monitor = new WindowsMicrophoneMonitor(snapshot::get);
    assertThatThrownBy(() -> monitor.bind(device)).isInstanceOf(IllegalStateException.class);
    snapshot.set(List.of(endpoint("prefix", "DRY (VT-4) extra", 1)));
    assertThatThrownBy(() -> monitor.bind(device)).isInstanceOf(IllegalStateException.class);
    snapshot.set(List.of(endpoint("dry", "DRY (VT-4)", 1)));
    var binding = monitor.bind(device);
    assertThat(binding.endpointId()).isEqualTo("dry");
    assertThatCode(() -> monitor.check(binding)).doesNotThrowAnyException();
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void sameNameActiveEndpointsAreAmbiguousEvenWithDifferentIds() {
    var monitor =
        new WindowsMicrophoneMonitor(
            () -> List.of(endpoint("one", device.name(), 1), endpoint("two", device.name(), 1)));
    assertThatThrownBy(() -> monitor.bind(device)).isInstanceOf(IllegalStateException.class);
  }

  /** 音声による対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void disconnectedDisabledOrReplacedEndpointStopsInsteadOfFollowingSameName() {
    var snapshot = new AtomicReference<>(List.of(endpoint("original", device.name(), 1)));
    var monitor = new WindowsMicrophoneMonitor(snapshot::get);
    var binding = monitor.bind(device);
    for (int state : List.of(2, 4, 8)) {
      snapshot.set(List.of(endpoint("original", device.name(), state)));
      assertThatThrownBy(() -> monitor.check(binding)).isInstanceOf(IllegalStateException.class);
    }
    snapshot.set(List.of(endpoint("replacement", device.name(), 1)));
    assertThatThrownBy(() -> monitor.check(binding)).isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> monitor.bind(device)).isInstanceOf(IllegalStateException.class);
    monitor.reselect(device.id());
    assertThat(monitor.bind(device).endpointId()).isEqualTo("replacement");
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void renamedEndpointIsNotSilentlyMappedByPartialMatch() {
    var snapshot = new AtomicReference<>(List.of(endpoint("original", device.name(), 1)));
    var monitor = new WindowsMicrophoneMonitor(snapshot::get);
    var binding = monitor.bind(device);
    snapshot.set(List.of(endpoint("original", "Renamed microphone", 1)));
    assertThatThrownBy(() -> monitor.check(binding)).isInstanceOf(IllegalStateException.class);
  }
}
