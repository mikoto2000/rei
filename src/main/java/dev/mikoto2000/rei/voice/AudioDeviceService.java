package dev.mikoto2000.rei.voice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/** 音声Deviceのユースケースを実現する。 */
public final class AudioDeviceService {
  /** 利用可能な項目。 */
  private final Supplier<List<AudioDevice>> catalog;

  /** 選択中識別子。 */
  private volatile String selectedId;

  /** 音声による対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AudioDeviceService() {
    this(AudioDeviceService::scan);
  }

  /** 指定された値や利用先を使って、AudioDeviceServiceを初期化する。 */
  public AudioDeviceService(Supplier<List<AudioDevice>> catalog) {
    this.catalog = Objects.requireNonNull(catalog);
  }

  /** 音声による対話に関するキャッシュの無効化選択内容を処理する。 */
  public void invalidateSelection() {
    selectedId = null;
  }

  /** 保存済みの状態を引き継ぐため、選択内容を復元する。 */
  public void restoreSelection(String id) {
    selectedId = id;
  }

  /** 音声による対話で用いるdevicesを返す。 */
  public List<AudioDevice> devices() {
    return List.copyOf(catalog.get());
  }

  /** 指定された条件に応じて、利用する対象を選ぶ。 */
  public void select(String id) {
    var matches = devices().stream().filter(d -> d.id().equals(id)).toList();
    if (matches.size() != 1)
      throw new IllegalArgumentException("Missing or ambiguous microphone identity");
    selectedId = id;
  }

  /** 音声による対話で用いる選択中を返す。 */
  public AudioDevice selected() {
    if (selectedId == null)
      throw new IllegalStateException("Select a microphone with /voice device set ID");
    var matches = devices().stream().filter(d -> d.id().equals(selectedId)).toList();
    if (matches.size() != 1)
      throw new IllegalStateException(
          "Selected microphone disconnected or ambiguous; select explicitly");
    return matches.getFirst();
  }

  /** 音声による対話で用いる説明を返す。 */
  static AudioDevice describe(Mixer.Info info) {
    String identity =
        info.getName()
            + "\u0000"
            + info.getDescription()
            + "\u0000"
            + info.getVendor()
            + "\u0000"
            + info.getVersion();
    try {
      String id =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(identity.getBytes(StandardCharsets.UTF_8)));
      return new AudioDevice(
          id, info.getName(), info.getDescription(), info.getVendor(), info.getVersion());
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 音声による対話で用いる調査を返す。 */
  private static List<AudioDevice> scan() {
    return Arrays.stream(AudioSystem.getMixerInfo())
        .filter(
            info ->
                Arrays.stream(AudioSystem.getMixer(info).getTargetLineInfo())
                    .anyMatch(line -> TargetDataLine.class.isAssignableFrom(line.getLineClass())))
        .map(AudioDeviceService::describe)
        .toList();
  }
}
