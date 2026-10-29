package dev.mikoto2000.rei.core.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** システムシェルのユースケースを実現する。 */
@Component
public class SystemShellService {

  /** 指定や設定に応じた対象を利用するため、シェルを確定する。 */
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

  /** 対話から使う共通操作で用いるシェルコマンド行を返す。 */
  public List<String> shellCommandLine(String shell, String command) {
    String shellName = shellName(shell);
    if (shellName.equals("powershell")
        || shellName.equals("powershell.exe")
        || shellName.equals("pwsh")
        || shellName.equals("pwsh.exe")) {
      // Windows command-line reconstruction can strip nested quotes in -Command.
      // Encode only after policy inspection of the original script; preserve its exact text.
      return List.of(
          shell,
          "-NoProfile",
          "-EncodedCommand",
          Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE)));
    }
    if (shellName.equals("cmd") || shellName.equals("cmd.exe")) {
      return List.of(shell, "/C", command);
    }
    return List.of(shell, "-lc", command);
  }

  /** 対話から使う共通操作で用いる対話シェルコマンド行を返す。 */
  public List<String> interactiveShellCommandLine(String shell) {
    String shellName = shellName(shell);
    if (shellName.equals("powershell")
        || shellName.equals("powershell.exe")
        || shellName.equals("pwsh")
        || shellName.equals("pwsh.exe")) {
      return List.of(shell, "-NoLogo");
    }
    return List.of(shell);
  }

  /** 対話から使う共通操作で用いるシェル名前を返す。 */
  private String shellName(String shell) {
    return Paths.get(shell).getFileName().toString().toLowerCase();
  }
}
