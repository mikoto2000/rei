package dev.mikoto2000.rei.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        .isEqualTo(
            List.of(
                "powershell",
                "-NoProfile",
                "-EncodedCommand",
                Base64.getEncoder()
                    .encodeToString("Write-Output hello".getBytes(StandardCharsets.UTF_16LE))));
    assertThat(service.shellCommandLine("cmd", "echo hello"))
        .isEqualTo(List.of("cmd", "/C", "echo hello"));
    assertThat(service.shellCommandLine("/bin/bash", "printf hello"))
        .isEqualTo(List.of("/bin/bash", "-lc", "printf hello"));
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
  void nativePowershellPreservesQuotedCommandsAndUnicode() throws Exception {
    var service = new SystemShellService();
    String script =
        "[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)\n"
            + "$text = \"quoted value; second\"\nWrite-Output $text\n"
            + "Write-Output '日本語の検証'\nexit 0";
    var process =
        new ProcessBuilder(service.shellCommandLine("powershell", script))
            .redirectErrorStream(true)
            .start();
    assertThat(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).isZero();
    assertThat(output).contains("quoted value", "second", "日本語の検証");
    var failure = new ProcessBuilder(service.shellCommandLine("powershell", "exit 7")).start();
    assertThat(failure.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    assertThat(failure.exitValue()).isEqualTo(7);
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
