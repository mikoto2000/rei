package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SystemShellServiceTest {

  @Test
  void resolvesShellFromEnvironment() {
    SystemShellService service = new SystemShellService();

    assertThat(service.resolveShell(Map.of("SHELL", "/bin/zsh"), "Windows 11")).isEqualTo("/bin/zsh");
  }

  @Test
  void resolvesDefaultShellByOperatingSystem() {
    SystemShellService service = new SystemShellService();

    assertThat(service.resolveShell(Map.of(), "Windows 11")).isEqualTo("powershell");
    assertThat(service.resolveShell(Map.of(), "Linux")).isEqualTo("bash");
    assertThat(service.resolveShell(Map.of(), "Mac OS X")).isEqualTo("bash");
  }

  @Test
  void buildsShellCommandLine() {
    SystemShellService service = new SystemShellService();

    assertThat(service.shellCommandLine("powershell", "Write-Output hello"))
        .isEqualTo(List.of("powershell", "-NoProfile", "-Command", "Write-Output hello"));
    assertThat(service.shellCommandLine("cmd", "echo hello"))
        .isEqualTo(List.of("cmd", "/C", "echo hello"));
    assertThat(service.shellCommandLine("/bin/bash", "printf hello"))
        .isEqualTo(List.of("/bin/bash", "-lc", "printf hello"));
  }

  @Test
  void buildsInteractiveShellCommandLine() {
    SystemShellService service = new SystemShellService();

    assertThat(service.interactiveShellCommandLine("powershell")).isEqualTo(List.of("powershell", "-NoLogo"));
    assertThat(service.interactiveShellCommandLine("pwsh")).isEqualTo(List.of("pwsh", "-NoLogo"));
    assertThat(service.interactiveShellCommandLine("cmd")).isEqualTo(List.of("cmd"));
    assertThat(service.interactiveShellCommandLine("/bin/bash")).isEqualTo(List.of("/bin/bash"));
  }
}
