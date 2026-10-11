package dev.mikoto2000.rei.chatgpt;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.chatgpt-plan.app-server")
public record PlanAppServerProperties(boolean enabled,Path runtimeDirectory,String distribution,String executable) {
  public PlanAppServerProperties {
    if(runtimeDirectory==null)runtimeDirectory=dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("app-server");
    runtimeDirectory=runtimeDirectory.toAbsolutePath().normalize();
    if(distribution==null)distribution="Ubuntu-24.04";
    if(enabled&&(!distribution.matches("[A-Za-z0-9_.-]{1,64}")||executable==null||!executable.matches("/[A-Za-z0-9_./-]{1,2048}")||executable.contains("/../")))throw new IllegalArgumentException("Explicit WSL distribution and absolute Linux Codex 0.154.0 executable required");
  }
}
