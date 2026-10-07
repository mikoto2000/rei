package dev.mikoto2000.rei.externalagent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import lombok.Data;

@Data
@ConfigurationProperties("rei.external-agents.codex")
public class CodexProperties {
  private boolean enabled = true;
  private boolean implementationEnabled = false;
  /** Trusted administrator-selected recipe, never supplied by external model output. */
  private String implementationTestCommand = "";
  private int implementationTestTimeoutSeconds = 30;
  /** Opt-in: the native CLI retains its own session files; Rei stores only the opaque UUID. */
  private boolean persistSessions = false;
  /** Opt-in accounting of native CLI turn usage in the parent Run/Goal budget. */
  private boolean inheritRunModelBudget = false;
  private boolean parallelReviewEnabled = false;
  private Duration parallelReviewTimeout = Duration.ofSeconds(120);
  public void setParallelReviewTimeout(Duration value){if(value==null||value.compareTo(Duration.ofMillis(100))<0||value.compareTo(Duration.ofSeconds(120))>0)throw new IllegalArgumentException("Parallel review timeout must be 100ms to 120s");parallelReviewTimeout=value;}
  private String command = "codex";
  private Duration totalTimeout = Duration.ofMinutes(20);
  private Duration inactivityTimeout = Duration.ofMinutes(5);
  private int maxOutputBytes = 4194304;
}
