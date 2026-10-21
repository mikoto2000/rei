package dev.mikoto2000.rei.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 対話シェルの結果と状態の契約を検証するテスト。 */
class InteractiveShellTest {
  /** 軽量CLIでの対話で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void selectsTheSameDefaultAndEnvironmentShellAsLegacy() {
    assertEquals(
        List.of("powershell", "-NoLogo"), InteractiveShell.command(Map.of(), "Windows 11"));
    assertEquals(List.of("bash"), InteractiveShell.command(Map.of(), "Linux"));
    assertEquals(
        List.of("C:\\Program Files\\PowerShell\\7\\pwsh.exe", "-NoLogo"),
        InteractiveShell.command(
            Map.of("SHELL", "C:\\Program Files\\PowerShell\\7\\pwsh.exe"), "Windows 11"));
    assertEquals(
        List.of("cmd.exe"), InteractiveShell.command(Map.of("SHELL", "cmd.exe"), "Windows 11"));
  }
}
