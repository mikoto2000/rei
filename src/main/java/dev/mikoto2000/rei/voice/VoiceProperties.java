package dev.mikoto2000.rei.voice;
import dev.mikoto2000.rei.core.datasource.ReiDataDirectory;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Getter @Setter
@ConfigurationProperties(prefix="rei.voice")
public class VoiceProperties {
  private String bundleDirectory=ReiDataDirectory.current().resolve("voice").toString();
  private String deviceId;
  private boolean confirmation;
  private volatile VoiceSettings settings=VoiceSettings.defaults();
  private volatile VoiceAdvancedOptions advanced=VoiceAdvancedOptions.defaults();
  public VoiceAdvancedOptions advanced(){return java.util.Objects.requireNonNull(advanced);}
  private volatile VoiceInferenceOptions inference=VoiceInferenceOptions.defaults();
  public VoiceInferenceOptions inference(){return java.util.Objects.requireNonNull(inference);}
  public VoiceSettings settings() { return settings; }
}