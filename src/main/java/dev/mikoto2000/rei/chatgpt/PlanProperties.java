package dev.mikoto2000.rei.chatgpt;

import java.nio.file.Path;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "rei.chatgpt-plan")
public record PlanProperties(boolean enabled, Path credentialDirectory, Set<String> allowedRoles) {
  public PlanProperties {
    if (credentialDirectory == null)
      credentialDirectory =
          dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current()
              .resolve("credentials/chatgpt-plan");
    allowedRoles = allowedRoles == null ? Set.of() : Set.copyOf(allowedRoles);
  }
}
