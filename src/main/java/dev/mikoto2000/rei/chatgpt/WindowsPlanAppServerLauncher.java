package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PERMISSION_DENIED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PROTOCOL;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.ROUTE_UNAVAILABLE;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * ChatGPTとの連携に必要なWindows計画アプリケーションサーバー起動を扱う。
 *
 * <p>Explicit MXC, no WSL/legacy fallback, selected own OAuth only in the server environment.
 */
public final class WindowsPlanAppServerLauncher implements PlanAppServerClient.Launcher {
  /** 無効状態プラグイン一覧。 */
  private static final String DISABLED_PLUGINS =
      """
      plugins = false
      remote_plugin = false
      plugin_sharing = false
      recommended_plugins = false
      skip_host_skill_discovery = true
      skill_mcp_dependency_install = false
      skill_search = false
      """;

  /** 期限一覧。 */
  private static final java.util.concurrent.ScheduledThreadPoolExecutor DEADLINES =
      new java.util.concurrent.ScheduledThreadPoolExecutor(
          1,
          r -> {
            var thread = new Thread(r, "rei-native-app-server-deadline");
            thread.setDaemon(true);
            return thread;
          });

  static {
    DEADLINES.setRemoveOnCancelPolicy(true);
  }

  /** 実行環境。 */
  private static final List<String> PLATFORM =
      List.of(
          "SystemRoot",
          "SystemDrive",
          "WINDIR",
          "ProgramFiles",
          "ProgramFiles(x86)",
          "ProgramW6432",
          "ProgramData",
          "ALLUSERSPROFILE");

  /** 設定。 */
  private final PlanAppServerProperties settings;

  /** 指定された値や利用先を使って、WindowsPlanAppServerLauncherを初期化する。 */
  public WindowsPlanAppServerLauncher(PlanAppServerProperties settings) {
    this.settings = settings;
  }

  /** ChatGPTとの連携で用いる形式のバージョンを返す。 */
  public String version() {
    return "0.162.1";
  }

  /** ChatGPTとの連携で用いる実行環境を返す。 */
  public String platform() {
    return "windows";
  }

  /** ChatGPTとの連携で用いる接続先を返す。 */
  public String authority() {
    return dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256(
        ("windows|" + settings.executable() + "|" + configuration())
            .getBytes(StandardCharsets.UTF_8));
  }

  /** 要求された処理を開始する。 */
  public PlanAppServerClient.Started start(
      TokenSet tokens, Duration timeout, BooleanSupplier stop) {
    if (!settings.enabled()
        || !settings.executionMode().equals("windows")
        || !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
      throw ChatGptException.of(ROUTE_UNAVAILABLE);
    var deadline = new PlanProcessDeadline(timeout);
    Path home = null;
    try {
      if (!Files.isRegularFile(Path.of(settings.executable())))
        throw ChatGptException.of(ROUTE_UNAVAILABLE);
      Files.createDirectories(settings.runtimeDirectory());
      if (Files.isSymbolicLink(settings.runtimeDirectory()))
        throw ChatGptException.of(PERMISSION_DENIED);
      home = Files.createTempDirectory(settings.runtimeDirectory(), "rei-plan-windows-");
      Path cwd = Files.createDirectory(home.resolve("workspace"));
      Files.writeString(home.resolve("config.toml"), configuration());
      if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
      deadline.remaining();
      var probe =
          process(home, cwd, null, List.of("--version"))
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      try {
        if (!probe.waitFor(deadline.probeWaitMillis(), TimeUnit.MILLISECONDS)) {
          probe.destroyForcibly();
          throw ChatGptException.of(TIMEOUT);
        }
        String output =
            new String(probe.getInputStream().readNBytes(256), StandardCharsets.UTF_8).strip();
        if (probe.exitValue() != 0 || !output.equals("codex-cli " + version()))
          throw ChatGptException.of(PROTOCOL);
      } finally {
        probe.destroyForcibly();
      }
      if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
      deadline.remaining();
      var child =
          process(home, cwd, tokens.accessToken(), List.of("app-server", "--listen", "stdio://"))
              .start();
      long remaining;
      try {
        remaining = deadline.remaining().toNanos();
      } catch (ChatGptException expired) {
        child.descendants().forEach(ProcessHandle::destroyForcibly);
        child.destroyForcibly();
        throw expired;
      }
      var expiry =
          DEADLINES.schedule(
              () -> {
                if (child.isAlive()) {
                  child.descendants().forEach(ProcessHandle::destroyForcibly);
                  child.destroyForcibly();
                }
              },
              remaining,
              TimeUnit.NANOSECONDS);
      child.onExit().thenRun(() -> expiry.cancel(false));
      Path ownedHome = home;
      return new PlanAppServerClient.Started(
          new AppServerProcessSession(
              child,
              () -> {
                child.descendants().forEach(ProcessHandle::destroy);
                child.destroy();
              },
              () -> {
                child.descendants().forEach(ProcessHandle::destroyForcibly);
                child.destroyForcibly();
              },
              () -> remove(ownedHome)),
          cwd.toString());
    } catch (ChatGptException e) {
      remove(home);
      throw e;
    } catch (Exception e) {
      remove(home);
      throw ChatGptException.of(ROUTE_UNAVAILABLE);
    }
  }

  /** ChatGPTとの連携で用いるプロセスを返す。 */
  ProcessBuilder process(Path home, Path cwd, String token, List<String> args) {
    var command = new ArrayList<String>();
    command.add(settings.executable());
    command.addAll(args);
    var process = new ProcessBuilder(command).directory(cwd.toFile());
    var env = process.environment();
    env.clear();
    for (String key : PLATFORM) {
      String value = System.getenv(key);
      if (value != null) env.put(key, value);
    }
    String systemRoot = env.get("SystemRoot"), systemDrive = env.get("SystemDrive");
    if (systemRoot == null || systemDrive == null || !systemDrive.matches("[A-Za-z]:"))
      throw ChatGptException.of(ROUTE_UNAVAILABLE);
    env.put("PATH", Path.of(systemRoot, "System32").toString());
    env.put("ComSpec", Path.of(systemRoot, "System32", "cmd.exe").toString());
    for (String key :
        List.of("HOME", "USERPROFILE", "LOCALAPPDATA", "APPDATA", "TEMP", "TMP", "CODEX_HOME"))
      env.put(key, home.toString());
    if (token != null) env.put("REI_PLAN_ACCESS_TOKEN", token);
    return process;
  }

  /** ChatGPTとの連携で用いる機能の構成を返す。 */
  String configuration() {
    String publicKeys =
        "\"SystemRoot\",\"SystemDrive\",\"WINDIR\",\"ProgramFiles\",\"ProgramFiles(x86)\",\"ProgramW6432\",\"ProgramData\",\"ALLUSERSPROFILE\",\"PATH\",\"ComSpec\",\"TEMP\",\"TMP\",\"USERPROFILE\",\"LOCALAPPDATA\",\"APPDATA\"";
    return WslPlanAppServerLauncher.configuration()
            .replace(
                "[permissions.rei-read.filesystem]",
                DISABLED_PLUGINS + "[permissions.rei-read.filesystem]")
            .replace(
                "[permissions.rei-read.network]",
                quote(settings.executable().replace('\\', '/'))
                    + " = \"read\"\n[permissions.rei-read.network]")
            .replace("inherit = \"none\"", "inherit = \"all\"\ninclude_only = [" + publicKeys + "]")
        + "\n[windows]\nsandbox = \"mxc\"\n";
  }

  /** ChatGPTとの連携で用いる引用を返す。 */
  private static String quote(String value) {
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid runtime path");
    }
  }

  /** 指定された対象を管理から取り除く。 */
  private static void remove(Path root) {
    if (root == null) return;
    // MXC releases directory handles asynchronously; close the walk before deleting its root.
    for (int attempt = 0; attempt < 20 && Files.exists(root); attempt++) {
      try {
        List<Path> owned;
        try (var paths = Files.walk(root)) {
          owned = paths.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path p : owned)
          if (p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
            Files.deleteIfExists(p);
        return;
      } catch (java.io.IOException ignored) {
        try {
          Thread.sleep(100);
        } catch (InterruptedException e) {
          // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
          Thread.currentThread().interrupt();
          return;
        }
      }
    }
  }
}
