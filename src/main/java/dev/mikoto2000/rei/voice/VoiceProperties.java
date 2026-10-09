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
  public VoiceSettings settings() { return settings; }
}