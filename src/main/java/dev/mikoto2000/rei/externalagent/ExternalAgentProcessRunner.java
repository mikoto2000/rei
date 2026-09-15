package dev.mikoto2000.rei.externalagent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static dev.mikoto2000.rei.externalagent.ExternalAgentResult.Status;

/** A single owned process tree; streams are drained concurrently with a shared byte budget. */
public class ExternalAgentProcessRunner {
  public record Output(Status status, String stdout, String stderr, Integer exitCode, long duration, boolean truncated) {}
  public Output run(List<String> command, Path root, String input, Duration total, Duration idle,
      int maxBytes, BooleanSupplier cancelled) {
    long start = System.nanoTime();
    AtomicLong activity = new AtomicLong(start);
    Capture capture = new Capture(maxBytes);
    Process process = null;
    Set<ProcessHandle> descendants = new LinkedHashSet<>();
    Status status = Status.SUCCESS;
    Integer exit = null;
    boolean interrupted = false;
    ExecutorService readers = Executors.newFixedThreadPool(3, Thread.ofPlatform().daemon().name("external-agent-io-", 0).factory());
    try {
      if (cancelled.getAsBoolean()) return new Output(Status.CANCELLED, "", "", null, 0, false);
      process = new ProcessBuilder(nativeArguments(command)).directory(root.toFile()).start();
      Process running = process;
      Future<?> stdout = readers.submit(() -> drain(running.getInputStream(), capture, false, activity));
      Future<?> stderr = readers.submit(() -> drain(running.getErrorStream(), capture, true, activity));
      readers.submit(() -> {
        try (var writer = running.getOutputStream()) { writer.write(input.getBytes(StandardCharsets.UTF_8)); }
        catch (IOException ignored) { /* Process may exit without consuming stdin. */ }
      });
      while (true) {
        running.descendants().forEach(descendants::add);
        if (cancelled.getAsBoolean()) { status = Status.CANCELLED; break; }
        long now = System.nanoTime();
        if (now - start >= total.toNanos()) { status = Status.TOTAL_TIMEOUT; break; }
        if (now - activity.get() >= idle.toNanos()) { status = Status.INACTIVITY_TIMEOUT; break; }
        if (running.waitFor(25, TimeUnit.MILLISECONDS)) {
          exit = running.exitValue(); status = exit == 0 ? Status.SUCCESS : Status.FAILED; break;
        }
      }
      cleanup(running, descendants);
      stdout.get(2, TimeUnit.SECONDS);
      stderr.get(2, TimeUnit.SECONDS);
    } catch (InterruptedException error) {
      interrupted = true; status = Status.CANCELLED;
    } catch (IOException error) {
      status = Status.UNAVAILABLE;
      byte[] diagnostic = dev.mikoto2000.rei.event.CredentialRedactor.redact(error.getMessage()).getBytes(StandardCharsets.UTF_8);
      capture.add(diagnostic, diagnostic.length, true);
    } catch (ExecutionException | TimeoutException error) {
      if (status == Status.SUCCESS) status = Status.FAILED;
    } finally {
      if (process != null) {
        cleanup(process, descendants);
        try { process.getInputStream().close(); } catch (IOException ignored) {}
        try { process.getErrorStream().close(); } catch (IOException ignored) {}
        try { process.getOutputStream().close(); } catch (IOException ignored) {}
      }
      readers.shutdownNow();
      try { readers.awaitTermination(2, TimeUnit.SECONDS); }
      catch (InterruptedException error) { interrupted = true; }
      if (interrupted) Thread.currentThread().interrupt();
    }
    return new Output(status, capture.text(false), capture.text(true), exit,
        Duration.ofNanos(System.nanoTime() - start).toMillis(), capture.truncated);
  }
  private static List<String> nativeArguments(List<String> command) {
    // JDK Windows legacy mode preserves shell-style quotes verbatim in the command line;
    // the native argv parser then consumes them. Encode literal quotes for the native parser.
    // Safe mode already performs this encoding. Never apply Windows encoding on Unix.
    if (!System.getProperty("os.name", "").startsWith("Windows")
        || "false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands"))) return command;
    List<String> encoded = new ArrayList<>(command);
    for (int i = 1; i < encoded.size(); i++) {
      String argument = encoded.get(i);
      if (!argument.contains("\"")) continue;
      StringBuilder quoted = new StringBuilder("\"");
      int backslashes = 0;
      for (char ch : argument.toCharArray()) {
        if (ch == '\\') { backslashes++; continue; }
        quoted.append("\\".repeat(ch == '"' ? backslashes * 2 + 1 : backslashes)).append(ch);
        backslashes = 0;
      }
      encoded.set(i, quoted.append("\\".repeat(backslashes * 2)).append('"').toString());
    }
    return encoded;
  }
  private static void drain(InputStream stream, Capture capture, boolean stderr, AtomicLong activity) {
    try (stream) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = stream.read(buffer)) != -1) {
        activity.set(System.nanoTime()); capture.add(buffer, read, stderr);
      }
    } catch (IOException ignored) { /* Closing the owned process closes its pipes. */ }
  }
  private static void cleanup(Process process, Set<ProcessHandle> known) {
    process.descendants().forEach(known::add);
    // Kill children while their parent can still reap them; retain handles across parent exit.
    List<ProcessHandle> children = new ArrayList<>(known);
    Collections.reverse(children);
    for (var child : children) if (child.isAlive()) child.destroyForcibly();
    for (var child : children) {
      try { child.onExit().get(500, TimeUnit.MILLISECONDS); }
      catch (Exception ignored) { }
    }
    if (process.isAlive()) process.destroyForcibly();
    try { process.waitFor(1, TimeUnit.SECONDS); }
    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
  }
  private static final class Capture {
    private final ByteArrayOutputStream stdout = new ByteArrayOutputStream();
    private final ByteArrayOutputStream stderr = new ByteArrayOutputStream();
    private int remaining;
    private boolean truncated;
    Capture(int bytes) { if (bytes < 1) throw new IllegalArgumentException("Output limit must be positive"); remaining = bytes; }
    synchronized void add(byte[] bytes, int size, boolean err) {
      int keep = Math.min(size, remaining);
      (err ? stderr : stdout).write(bytes, 0, keep);
      remaining -= keep; truncated |= keep < size;
    }
    synchronized String text(boolean err) { return (err ? stderr : stdout).toString(StandardCharsets.UTF_8); }
  }
}
