package dev.mikoto2000.rei.externalagent;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("rei.external-agents.claude")
public class ClaudeCodeProperties {
  private boolean enabled=false;
  private boolean persistSessions=false;
  private boolean fixProposalsEnabled=false;
  private boolean implementationEnabled=false;
  private String implementationTestCommand="";
  private int implementationTestTimeoutSeconds=30;
  private boolean parallelReviewEnabled=false;
  private Duration parallelReviewTimeout=Duration.ofSeconds(120);
  public void setParallelReviewTimeout(Duration value){if(value==null || value.compareTo(Duration.ofMillis(100))<0 || value.compareTo(Duration.ofSeconds(120))>0)throw new IllegalArgumentException("Parallel review timeout must be 100ms to 120s");parallelReviewTimeout=value;}
  private java.nio.file.Path nativeSessionDirectory=dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("claude-review-sessions");
  private String command="claude";
  private Duration totalTimeout=Duration.ofMinutes(5);
  private Duration inactivityTimeout=Duration.ofMinutes(2);
  private int maxOutputBytes=1048576;
}
