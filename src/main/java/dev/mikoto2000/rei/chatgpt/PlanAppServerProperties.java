package dev.mikoto2000.rei.chatgpt;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rei.chatgpt-plan.app-server")
public record PlanAppServerProperties(boolean enabled,Path runtimeDirectory,String distribution,String executable,String executionMode) {
  /** Existing programmatic WSL construction remains explicit. Configuration defaults to Windows. */
  public PlanAppServerProperties(boolean enabled,Path runtimeDirectory,String distribution,String executable){
    this(enabled,runtimeDirectory,distribution,executable,"wsl");
  }
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public PlanAppServerProperties {
    if(runtimeDirectory==null)runtimeDirectory=dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("app-server");
    runtimeDirectory=runtimeDirectory.toAbsolutePath().normalize();
    if(distribution==null)distribution="Ubuntu-24.04";
    if(executionMode==null)executionMode="windows";
    if(!java.util.Set.of("windows","wsl").contains(executionMode))throw new IllegalArgumentException("Explicit windows or wsl execution mode required");
    if(enabled&&executionMode.equals("wsl")&&(!distribution.matches("[A-Za-z0-9_.-]{1,64}")||executable==null||!executable.matches("/[A-Za-z0-9_./-]{1,2048}")||executable.contains("/../")))throw new IllegalArgumentException("Explicit WSL distribution and absolute Linux Codex 0.154.0 executable required");
    if(enabled&&executionMode.equals("windows")) {
      if(executable==null||!executable.matches("(?i)[A-Z]:[\\\\/].+\\.exe")||executable.contains("\n")||executable.contains("\r"))throw new IllegalArgumentException("Absolute native Windows Codex 0.162.1 .exe required");
      executable=Path.of(executable).normalize().toString();
    }
  }
}
