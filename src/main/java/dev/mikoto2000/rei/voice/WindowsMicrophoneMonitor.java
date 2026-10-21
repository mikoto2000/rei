package dev.mikoto2000.rei.voice;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 音声による対話に必要なWindowsマイク排他制御を扱う。
 *
 * <p>Pins a Java Sound selection to one active Windows endpoint; never follows the default.
 */
public final class WindowsMicrophoneMonitor {
  /**
   * 音声による対話で受け渡す関連付けを表す。
   *
   * @param endpointId 接続先識別子
   * @param name 名前
   */
  public record Binding(String endpointId, String name) {}

  /** 保有情報。 */
  private final Supplier<List<WindowsAudioEndpoints.Endpoint>> inventory;

  /** 選択中接続先一覧。 */
  private final Map<String, String> selectedEndpoints = new HashMap<>();

  /** 指定された値や利用先を使って、WindowsMicrophoneMonitorを初期化する。 */
  public WindowsMicrophoneMonitor(Supplier<List<WindowsAudioEndpoints.Endpoint>> inventory) {
    this.inventory = Objects.requireNonNull(inventory);
  }

  /** 後続の照会で対応する対象を見つけられるよう、Windowsマイク排他制御を関連付ける。 */
  public synchronized Binding bind(AudioDevice device) {
    var matches =
        inventory.get().stream()
            .filter(e -> e.state() == 1 && e.name().equals(device.name()))
            .toList();
    if (matches.size() != 1)
      throw new IllegalStateException(
          "Microphone endpoint is missing or ambiguous; select it explicitly");
    var endpoint = matches.getFirst();
    var previous = selectedEndpoints.get(device.id());
    if (previous != null && !previous.equals(endpoint.id()))
      throw new IllegalStateException(
          "Microphone endpoint changed; explicit reselection is required");
    selectedEndpoints.put(device.id(), endpoint.id());
    return new Binding(endpoint.id(), endpoint.name());
  }

  /** 後続の処理を進められるか、現在の条件を確認する。 */
  public void check(Binding binding) {
    var matches =
        inventory.get().stream().filter(e -> e.id().equals(binding.endpointId())).toList();
    if (matches.size() != 1
        || matches.getFirst().state() != 1
        || !matches.getFirst().name().equals(binding.name()))
      throw new IllegalStateException("Selected microphone endpoint is no longer active");
  }

  /** 音声による対話に関するreselectを処理する。 */
  public synchronized void reselect(String deviceId) {
    selectedEndpoints.remove(deviceId);
  }
}
