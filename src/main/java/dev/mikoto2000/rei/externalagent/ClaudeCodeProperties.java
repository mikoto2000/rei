package dev.mikoto2000.rei.externalagent;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("rei.external-agents.claude")
public class ClaudeCodeProperties {
  private boolean enabled=false;
  private String command="claude";
  private Duration totalTimeout=Duration.ofMinutes(5);
  private Duration inactivityTimeout=Duration.ofMinutes(2);
  private int maxOutputBytes=1048576;
}
