package dev.mikoto2000.rei.externalagent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;

@Data
@ConfigurationProperties("rei.external-agents.codex")
public class CodexProperties {
  private boolean enabled = true;
  private String command = "codex";
  private Duration totalTimeout = Duration.ofMinutes(20);
  private Duration inactivityTimeout = Duration.ofMinutes(5);
  private int maxOutputBytes = 4194304;
}
