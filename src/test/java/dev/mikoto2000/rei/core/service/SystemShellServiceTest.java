package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** システムシェルサービスの結果と状態の契約を検証するテスト。 */
class SystemShellServiceTest {

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resolvesShellFromEnvironment() {
    SystemShellService service = new SystemShellService();

    assertThat(service.resolveShell(Map.of("SHELL", "/bin/zsh"), "Windows 11"))
        .isEqualTo("/bin/zsh");
  }

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void resolvesDefaultShellByOperatingSystem() {
    SystemShellService service = new SystemShellService();

    assertThat(service.resolveShell(Map.of(), "Windows 11")).isEqualTo("powershell");
    assertThat(service.resolveShell(Map.of(), "Linux")).isEqualTo("bash");
    assertThat(service.resolveShell(Map.of(), "Mac OS X")).isEqualTo("bash");
  }

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
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

  /** 対話から使う共通操作で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void buildsInteractiveShellCommandLine() {
    SystemShellService service = new SystemShellService();

    assertThat(service.interactiveShellCommandLine("powershell"))
        .isEqualTo(List.of("powershell", "-NoLogo"));
    assertThat(service.interactiveShellCommandLine("pwsh")).isEqualTo(List.of("pwsh", "-NoLogo"));
    assertThat(service.interactiveShellCommandLine("cmd")).isEqualTo(List.of("cmd"));
    assertThat(service.interactiveShellCommandLine("/bin/bash")).isEqualTo(List.of("/bin/bash"));
  }
}
