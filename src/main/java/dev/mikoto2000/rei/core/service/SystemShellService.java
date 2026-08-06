package dev.mikoto2000.rei.core.service;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

@Component
public class SystemShellService {

  public String resolveShell(Map<String, String> environment, String osName) {
    String shell = environment == null ? null : environment.get("SHELL");
    if (shell != null && !shell.isBlank()) {
      return shell;
    }
    String normalizedOsName = osName == null ? "" : osName.toLowerCase();
    if (normalizedOsName.contains("win")) {
      return "powershell";
    }
    return "bash";
  }

  public List<String> shellCommandLine(String shell, String command) {
    String shellName = shellName(shell);
    if (shellName.equals("powershell") || shellName.equals("powershell.exe") || shellName.equals("pwsh")
        || shellName.equals("pwsh.exe")) {
      return List.of(shell, "-NoProfile", "-Command", command);
    }
    if (shellName.equals("cmd") || shellName.equals("cmd.exe")) {
      return List.of(shell, "/C", command);
    }
    return List.of(shell, "-lc", command);
  }

  public List<String> interactiveShellCommandLine(String shell) {
    String shellName = shellName(shell);
    if (shellName.equals("powershell") || shellName.equals("powershell.exe") || shellName.equals("pwsh")
        || shellName.equals("pwsh.exe")) {
      return List.of(shell, "-NoLogo");
    }
    return List.of(shell);
  }

  private String shellName(String shell) {
    return Paths.get(shell).getFileName().toString().toLowerCase();
  }
}
