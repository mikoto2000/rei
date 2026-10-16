package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PERMISSION_DENIED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.PROTOCOL;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.ROUTE_UNAVAILABLE;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TIMEOUT;

import java.io.IOException;
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
 * No shell wrapper, token argument or normal Codex home. Native tools disabled and OS reads
 * restricted.
 */
public final class WslPlanAppServerLauncher implements PlanAppServerClient.Launcher {
  private final PlanAppServerProperties settings;

  public WslPlanAppServerLauncher(PlanAppServerProperties settings) {
    this.settings = settings;
  }

  public String authority() {
    return dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256(
        (settings.distribution() + "|" + settings.executable() + "|" + configuration())
            .getBytes(StandardCharsets.UTF_8));
  }

  public PlanAppServerClient.Started start(
      TokenSet tokens, Duration timeout, BooleanSupplier stop) {
    if (!settings.enabled()
        || !System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win"))
      throw ChatGptException.of(ROUTE_UNAVAILABLE);
    var deadline = new PlanProcessDeadline(timeout);
    Path home = null;
    try {
      Files.createDirectories(settings.runtimeDirectory());
      if (Files.isSymbolicLink(settings.runtimeDirectory()))
        throw ChatGptException.of(PERMISSION_DENIED);
      home = Files.createTempDirectory(settings.runtimeDirectory(), "rei-plan-");
      Path cwd = Files.createDirectory(home.resolve("workspace"));
      Files.writeString(
          home.resolve("config.toml"),
          configuration()
              .replace(
                  "[permissions.rei-read.network]",
                  "\"" + settings.executable() + "\" = \"read\"\n[permissions.rei-read.network]"));
      String linuxCwd = linuxPath(cwd);
      if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
      var version =
          process(home, linuxCwd, null, List.of("--version"), deadline.wslWatchdogDuration())
              .start();
      try {
        if (!version.waitFor(deadline.probeWaitMillis(), TimeUnit.MILLISECONDS)) {
          version.destroyForcibly();
          throw ChatGptException.of(TIMEOUT);
        }
        byte[] output = version.getInputStream().readNBytes(256);
        if (version.exitValue() != 0
            || !new String(output, StandardCharsets.UTF_8).strip().equals("codex-cli 0.154.0"))
          throw ChatGptException.of(PROTOCOL);
      } finally {
        version.destroyForcibly();
      }
      if (stop.getAsBoolean()) throw ChatGptException.of(CANCELLED);
      var child =
          process(
                  home,
                  linuxCwd,
                  tokens.accessToken(),
                  List.of("app-server", "--listen", "stdio://"),
                  deadline.wslWatchdogDuration())
              .start();
      Path ownedHome = home;
      return new PlanAppServerClient.Started(
          new AppServerProcessSession(
              child,
              () -> stopLinux(ownedHome, "TERM"),
              () -> stopLinux(ownedHome, "KILL"),
              () -> remove(ownedHome)),
          linuxCwd);
    } catch (ChatGptException e) {
      remove(home);
      throw e;
    } catch (Exception e) {
      remove(home);
      throw ChatGptException.of(ROUTE_UNAVAILABLE);
    }
  }

  ProcessBuilder process(
      Path home, String linuxCwd, String token, List<String> arguments, String watchdogDuration) {
    if (watchdogDuration == null || !watchdogDuration.matches("[1-9][0-9]{0,3}s"))
      throw new IllegalArgumentException("Invalid Plan process watchdog duration");
    String systemRoot = System.getenv("SystemRoot");
    if (systemRoot == null) throw ChatGptException.of(ROUTE_UNAVAILABLE);
    var command =
        new ArrayList<String>(
            List.of(
                Path.of(systemRoot, "System32", "wsl.exe").toString(),
                "--distribution",
                settings.distribution(),
                "--cd",
                linuxCwd,
                "--exec"));
    if (!arguments.isEmpty() && arguments.getFirst().equals("app-server"))
      command.addAll(
          List.of(
              "/bin/sh",
              "-c",
              "printf '%s\\n' \"$$\" > \"$CODEX_HOME/process.pid\"; exec \"$@\"",
              "rei-plan-launcher",
              "/usr/bin/timeout",
              "--signal=TERM",
              "--kill-after=1s",
              watchdogDuration));
    command.add(settings.executable());
    command.addAll(arguments);
    var builder = new ProcessBuilder(command).directory(home.toFile());
    var env = builder.environment();
    env.clear();
    for (String name : List.of("SystemRoot", "TEMP", "TMP")) {
      String value = System.getenv(name);
      if (value != null) env.put(name, value);
    }
    env.put("PATH", systemRoot + "\\System32");
    env.put("CODEX_HOME", home.toString());
    env.put("HOME", home.toString());
    env.put("WSLENV", "CODEX_HOME/p:HOME/p" + (token == null ? "" : ":REI_PLAN_ACCESS_TOKEN/u"));
    if (token != null) env.put("REI_PLAN_ACCESS_TOKEN", token);
    return builder;
  }

  private void stopLinux(Path home, String signal) {
    try {
      String pid = Files.readString(home.resolve("process.pid")).strip();
      if (!pid.matches("[1-9][0-9]{0,9}") || Long.parseLong(pid) <= 1) return;
      // Check the dedicated home in the process environment before signalling its private timeout
      // group.
      String script =
          "test -r /proc/\"$1\"/environ || exit 0; tr '\\000' '\\n' < /proc/\"$1\"/environ | grep -F -x -- \"CODEX_HOME=$2\" >/dev/null || exit 0; kill -\"$3\" -- -\"$1\" 2>/dev/null || kill -\"$3\" \"$1\" 2>/dev/null";
      var base = process(home, linuxPath(home), null, List.of("--version"), "10s");
      var command =
          List.of(
              base.command().getFirst(),
              "--distribution",
              settings.distribution(),
              "--exec",
              "/bin/sh",
              "-c",
              script,
              "rei-plan-stop",
              pid,
              linuxPath(home),
              signal);
      var kill = new ProcessBuilder(command);
      kill.environment().clear();
      kill.environment().putAll(base.environment());
      kill.redirectOutput(ProcessBuilder.Redirect.DISCARD);
      kill.redirectError(ProcessBuilder.Redirect.DISCARD);
      var child = kill.start();
      if (!child.waitFor(1500, TimeUnit.MILLISECONDS)) child.destroyForcibly();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    } catch (Exception ignored) {
    }
  }

  static String linuxPath(Path path) {
    String value = path.toAbsolutePath().normalize().toString().replace('\\', '/');
    if (!value.matches("[A-Za-z]:/.*") || value.contains("\n") || value.contains("\r"))
      throw ChatGptException.of(PERMISSION_DENIED);
    return "/mnt/" + Character.toLowerCase(value.charAt(0)) + value.substring(2);
  }

  static String configuration() {
    return """
      model_provider = "rei_plan"
      default_permissions = "rei-read"
      web_search = "disabled"
      [model_providers.rei_plan]
      name = "Rei own ChatGPT Plan"
      base_url = "https://api.openai.com/v1"
      env_key = "REI_PLAN_ACCESS_TOKEN"
      wire_api = "responses"
      requires_openai_auth = false
      supports_websockets = false
      request_max_retries = 0
      stream_max_retries = 0
      [features]
      shell_tool = false
      multi_agent = false
      unified_exec = false
      apps = false
      browser_use = false
      computer_use = false
      view_image = false
      goals = false
      guardian_approval = false
      worktrees = false
      unbounded_connection_retries = false
      [permissions.rei-read.filesystem]
      ":root" = "deny"
      ":minimal" = "read"
      ":workspace_roots" = "read"
      [permissions.rei-read.network]
      enabled = false
      [shell_environment_policy]
      inherit = "none"
      """;
  }

  private static void remove(Path root) {
    if (root == null) return;
    // Only the newly created dedicated home; never follow links or recurse into external roots.
    try (var paths = Files.walk(root)) {
      for (Path p : paths.sorted(Comparator.reverseOrder()).toList())
        if (p.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()))
          Files.deleteIfExists(p);
    } catch (IOException ignored) {
    }
  }
}
