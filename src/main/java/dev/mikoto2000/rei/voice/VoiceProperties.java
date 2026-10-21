package dev.mikoto2000.rei.voice;

import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 音声の設定値をまとめ、利用環境に応じた動作を指定できるようにする。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.voice")
public class VoiceProperties {
  /** まとまりディレクトリ。 */
  private String bundleDirectory = ReiDataDirectory.current().resolve("voice").toString();

  /** device識別子。 */
  private String deviceId;

  /** 確認。 */
  private boolean confirmation;

  /** 自動開始。 */
  private boolean autoStart;

  /** 設定。 */
  private volatile VoiceSettings settings = VoiceSettings.defaults();

  /** 音声処理の詳細設定。 */
  private volatile VoiceAdvancedOptions advanced = VoiceAdvancedOptions.defaults();

  /** 音声による対話で用いるadvancedを返す。 */
  public VoiceAdvancedOptions advanced() {
    return java.util.Objects.requireNonNull(advanced);
  }

  /** 推定。 */
  private volatile VoiceInferenceOptions inference = VoiceInferenceOptions.defaults();

  /** 音声による対話で用いる推定を返す。 */
  public VoiceInferenceOptions inference() {
    return java.util.Objects.requireNonNull(inference);
  }

  /** 音声による対話で用いる設定を返す。 */
  public VoiceSettings settings() {
    return settings;
  }
}
