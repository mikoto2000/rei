package dev.mikoto2000.rei.chatgpt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** 判定期間計画アプリケーションサーバー起動の結果と状態の契約を検証するテスト。 */
@EnabledOnOs(OS.WINDOWS)
class WindowsPlanAppServerLauncherTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path home;

  /** 検証条件を再現するための関連付け。 */
  @org.springframework.boot.context.properties.EnableConfigurationProperties(
      PlanAppServerProperties.class)
  static class Binding {}

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void configurationBindingSelectsNativeWindowsByDefault() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withUserConfiguration(Binding.class)
        .withPropertyValues(
            "rei.chatgpt-plan.app-server.enabled=true",
            "rei.chatgpt-plan.app-server.executable=C:/Codex/codex.exe")
        .run(
            context -> {
              assertNull(context.getStartupFailure());
              assertEquals(
                  "windows", context.getBean(PlanAppServerProperties.class).executionMode());
            });
  }

  /** ChatGPTとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void windowsIsDefaultAndRequiresNativeExe() {
    var p = new PlanAppServerProperties(true, home, null, "C:/Program Files/Codex/codex.exe", null);
    assertEquals("windows", p.executionMode());
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlanAppServerProperties(true, home, null, "/opt/codex", "windows"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlanAppServerProperties(true, home, null, "C:/codex.cmd", "windows"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PlanAppServerProperties(true, home, null, "C:/codex.exe", "auto"));
  }

  /** ChatGPTとの連携で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void directProcessHasOwnHomeNoTokenArgumentsAndOnlyPlatformEnvironment() {
    var p =
        new PlanAppServerProperties(
            true, home, null, "C:/Program Files/Codex/codex.exe", "windows");
    var launcher = new WindowsPlanAppServerLauncher(p);
    var child =
        launcher.process(
            home,
            home.resolve("workspace"),
            "selected-token",
            List.of("app-server", "--listen", "stdio://"));
    assertTrue(child.command().getFirst().endsWith("codex.exe"));
    assertFalse(child.command().toString().contains("selected-token"));
    assertFalse(child.command().toString().contains("wsl"));
    assertEquals("selected-token", child.environment().get("REI_PLAN_ACCESS_TOKEN"));
    assertEquals(home.toString(), child.environment().get("CODEX_HOME"));
    assertEquals(home.toString(), child.environment().get("USERPROFILE"));
    assertFalse(child.environment().containsKey("OPENAI_API_KEY"));
    assertFalse(child.environment().containsKey("WSLENV"));
    assertEquals(System.getenv("SystemDrive"), child.environment().get("SystemDrive"));
    String config = launcher.configuration();
    assertTrue(config.contains("sandbox = \"mxc\""));
    assertTrue(config.indexOf("web_search = \"disabled\"") < config.indexOf("[windows]"));
    assertTrue(config.contains("shell_tool = false"));
    assertTrue(config.contains("plugins = false"));
    assertTrue(config.contains("remote_plugin = false"));
    assertTrue(config.contains("include_only"));
    assertFalse(config.contains("selected-token"));
    assertEquals("0.162.1", launcher.version());
    assertEquals("windows", launcher.platform());
  }
}
