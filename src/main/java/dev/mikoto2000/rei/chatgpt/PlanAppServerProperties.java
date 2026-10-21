package dev.mikoto2000.rei.chatgpt;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ChatGPTとの連携で受け渡す計画アプリケーションサーバー設定を表す。
 *
 * @param enabled 有効状態
 * @param runtimeDirectory 実行環境ディレクトリ
 * @param distribution 分布
 * @param executable 実行可能な
 * @param executionMode 実行動作モード
 */
@ConfigurationProperties("rei.chatgpt-plan.app-server")
public record PlanAppServerProperties(
    boolean enabled,
    Path runtimeDirectory,
    String distribution,
    String executable,
    String executionMode) {
  /**
   * ChatGPTとの連携を利用するため、指定を補って共通の初期化へ引き渡す。
   *
   * <p>Existing programmatic WSL construction remains explicit. Configuration defaults to Windows.
   */
  public PlanAppServerProperties(
      boolean enabled, Path runtimeDirectory, String distribution, String executable) {
    this(enabled, runtimeDirectory, distribution, executable, "wsl");
  }

  /** 受け取った情報からPlanAppServerPropertiesを作成する。 */
  @org.springframework.boot.context.properties.bind.ConstructorBinding
  public PlanAppServerProperties {
    if (runtimeDirectory == null)
      runtimeDirectory =
          dev.mikoto2000.rei.core.datasource.ReiDataDirectory.current().resolve("app-server");
    runtimeDirectory = runtimeDirectory.toAbsolutePath().normalize();
    if (distribution == null) distribution = "Ubuntu-24.04";
    if (executionMode == null) executionMode = "windows";
    if (!java.util.Set.of("windows", "wsl").contains(executionMode))
      throw new IllegalArgumentException("Explicit windows or wsl execution mode required");
    if (enabled
        && executionMode.equals("wsl")
        && (!distribution.matches("[A-Za-z0-9_.-]{1,64}")
            || executable == null
            || !executable.matches("/[A-Za-z0-9_./-]{1,2048}")
            || executable.contains("/../")))
      throw new IllegalArgumentException(
          "Explicit WSL distribution and absolute Linux Codex 0.154.0 executable required");
    if (enabled && executionMode.equals("windows")) {
      if (executable == null
          || !executable.matches("(?i)[A-Z]:[\\\\/].+\\.exe")
          || executable.contains("\n")
          || executable.contains("\r"))
        throw new IllegalArgumentException("Absolute native Windows Codex 0.162.1 .exe required");
      executable = Path.of(executable).normalize().toString();
    }
  }
}
