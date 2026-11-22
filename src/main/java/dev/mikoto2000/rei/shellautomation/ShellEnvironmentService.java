package dev.mikoto2000.rei.shellautomation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded, cached inspection of the shell actually selected by the existing resolver. */
public final class ShellEnvironmentService {
  public record Environment(
      String os,
      String shell,
      String executable,
      String version,
      String probeStatus,
      String outputEncoding) {}

  private final java.util.function.BiFunction<Map<String, String>, String, String> resolver;
  private final java.util.function.BiFunction<String, String, List<String>> commandLines;
  private String cacheKey;
  private Environment cached;
  private long expires;

  public ShellEnvironmentService(
      java.util.function.BiFunction<Map<String, String>, String, String> resolver,
      java.util.function.BiFunction<String, String, List<String>> commandLines) {
    this.resolver = resolver;
    this.commandLines = commandLines;
  }

  public Environment current() {
    return inspect(System.getenv(), System.getProperty("os.name"));
  }

  public synchronized Environment inspect(Map<String, String> environment, String os) {
    String shell = resolver.apply(environment, os);
    String key = os + "\n" + shell + "\n" + value(environment, "PATH");
    if (key.equals(cacheKey) && System.nanoTime() - expires < 0) return cached;
    String executable = resolveExecutable(shell, environment, os);
    String version = "unknown";
    String status = "not-found";
    if (executable != null) {
      String name = kind(executable);
      List<String> probe =
          switch (name) {
            case "powershell", "pwsh" ->
                commandLines.apply(executable, "$PSVersionTable.PSVersion.ToString()");
            case "cmd" -> List.of(executable, "/d", "/c", "ver");
            case "bash", "zsh" -> List.of(executable, "--version");
            default -> List.of();
          };
      status = "unsupported-probe";
      if (!probe.isEmpty()) {
        Process process = null;
        try {
          // Probe output is tiny, but drain concurrently so even a misconfigured executable cannot
          // block.
          process = new ProcessBuilder(probe).redirectErrorStream(true).start();
          Process owned = process;
          var output =
              java.util.concurrent.CompletableFuture.supplyAsync(
                  () -> ProcessOutputCapture.read(owned.getInputStream(), 4096));
          if (!process.waitFor(2, TimeUnit.SECONDS)) {
            status = "timed-out";
          } else {
            var captured = output.get(250, TimeUnit.MILLISECONDS);
            status =
                process.exitValue() == 0 && captured.readError() == null && !captured.truncated()
                    ? "available"
                    : "probe-failed";
            if (status.equals("available")) {
              version = captured.text().strip().lines().findFirst().orElse("unknown");
              if (version.length() > 160) version = version.substring(0, 160);
            }
          }
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          status = "interrupted";
        } catch (IOException
            | java.util.concurrent.ExecutionException
            | java.util.concurrent.TimeoutException failure) {
          status = "probe-failed";
        } finally {
          if (process != null) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
            try {
              process.getInputStream().close();
            } catch (IOException ignored) {
              /* owned probe */
            }
          }
        }
      }
    }
    cached =
        new Environment(
            os,
            kind(shell),
            executable == null ? shell : executable,
            version,
            status,
            "UTF-8 (PowerShell configured; native programs must emit UTF-8)");
    cacheKey = key;
    expires =
        System.nanoTime() + Duration.ofSeconds(status.equals("available") ? 600 : 30).toNanos();
    return cached;
  }

  public synchronized void invalidate() {
    cached = null;
    cacheKey = null;
  }

  /** Checks only requested executable names; it never runs them or returns the full PATH. */
  public Map<String, String> findExecutables(List<String> names) {
    if (names == null) return Map.of();
    if (names.size() > 16)
      throw new IllegalArgumentException("Specify at most 16 executable names");
    var found = new java.util.LinkedHashMap<String, String>();
    for (String name : names) {
      if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.+-]{0,79}"))
        throw new IllegalArgumentException("Executable lookup requires a bare executable name");
      String path = resolveExecutable(name, System.getenv(), System.getProperty("os.name"));
      found.put(name, path == null ? "not-found" : path);
    }
    return java.util.Collections.unmodifiableMap(found);
  }

  public static String kind(String executable) {
    String name = executable.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
    return name.endsWith(".exe") ? name.substring(0, name.length() - 4) : name;
  }

  static String resolveExecutable(String shell, Map<String, String> environment, String os) {
    Path supplied = Path.of(shell);
    if (supplied.isAbsolute() || shell.contains("/") || shell.contains("\\"))
      return Files.isRegularFile(supplied)
          ? supplied.toAbsolutePath().normalize().toString()
          : null;
    boolean windows = os != null && os.toLowerCase(Locale.ROOT).contains("win");
    String path = value(environment, "PATH");
    for (String entry : path.split(java.util.regex.Pattern.quote(windows ? ";" : ":"))) {
      if (entry.isBlank()) continue; // Do not implicitly search the current project.
      for (String suffix :
          windows && !shell.toLowerCase(Locale.ROOT).endsWith(".exe")
              ? List.of(".exe", "", ".cmd", ".bat")
              : List.of("")) {
        try {
          Path candidate = Path.of(entry.replace("\"", "")).resolve(shell + suffix);
          if (Files.isRegularFile(candidate) && (windows || Files.isExecutable(candidate)))
            return candidate.toAbsolutePath().normalize().toString();
        } catch (java.nio.file.InvalidPathException ignored) {
          /* Ignore malformed PATH entries. */
        }
      }
    }
    return null;
  }

  private static String value(Map<String, String> environment, String key) {
    if (environment == null) return "";
    return environment.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(key))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse("");
  }
}
