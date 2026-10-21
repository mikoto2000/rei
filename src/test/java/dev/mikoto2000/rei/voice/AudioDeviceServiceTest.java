package dev.mikoto2000.rei.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 音声Deviceサービスの結果と状態の契約を検証するテスト。 */
class AudioDeviceServiceTest {
  /** 検証に使うdry。 */
  final AudioDevice dry = new AudioDevice("dry", "DRY (VT-4)", "input", "vendor", "1");

  /** 明示的な選択内容を必要とすることを確認する。 */
  @Test
  void requiresExplicitSelectionAndRejectsDisconnectedOrAmbiguousIdentity() {
    var service = new AudioDeviceService(() -> List.of(dry));
    assertThatThrownBy(() -> service.selected()).isInstanceOf(IllegalStateException.class);
    service.select("dry");
    assertThat(service.selected()).isEqualTo(dry);
    assertThatThrownBy(() -> service.select("missing"))
        .isInstanceOf(IllegalArgumentException.class);
    var duplicate = new AudioDeviceService(() -> List.of(dry, dry));
    assertThatThrownBy(() -> duplicate.select("dry")).isInstanceOf(IllegalArgumentException.class);
  }

  /** 音声による対話で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void disconnectedSelectionNeverFallsBack() {
    var devices = new java.util.concurrent.atomic.AtomicReference<>(List.of(dry));
    var service = new AudioDeviceService(devices::get);
    service.select("dry");
    devices.set(List.of(new AudioDevice("other", "other", "input", "vendor", "1")));
    assertThatThrownBy(() -> service.selected()).isInstanceOf(IllegalStateException.class);
  }
}
