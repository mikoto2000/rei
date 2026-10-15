package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** Bounded stdio and owned-process cleanup shared by both launchers. */
final class AppServerProcessSession implements PlanAppServerRpc.Session {
  private static final Object EOF = new Object();
  private final BlockingQueue<Object> lines = new ArrayBlockingQueue<>(64);
  private final Process process;
  private final Runnable terminate, kill, cleanup;
  private final BufferedWriter writer;

  AppServerProcessSession(Process process, Runnable terminate, Runnable kill, Runnable cleanup) {
    this.process = process;
    this.terminate = terminate;
    this.kill = kill;
    this.cleanup = cleanup;
    writer =
        new BufferedWriter(
            new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
    Thread.ofVirtual()
        .start(
            () -> {
              try (var in = new BufferedInputStream(process.getInputStream())) {
                var line = new ByteArrayOutputStream();
                int total = 0, value;
                while ((value = in.read()) != -1) {
                  if (++total > 2 * 1024 * 1024 || line.size() >= 65536) throw new IOException();
                  if (value == '\n') {
                    if (!lines.offer(line.toString(StandardCharsets.UTF_8), 1, TimeUnit.SECONDS))
                      throw new IOException();
                    line.reset();
                  } else line.write(value);
                }
                if (line.size() > 0) lines.offer(line.toString(StandardCharsets.UTF_8));
              } catch (Exception ignored) {
                process.destroyForcibly();
              } finally {
                while (!lines.offer(EOF)) {
                  lines.poll();
                }
              }
            });
    Thread.ofVirtual()
        .start(
            () -> {
              try (var in = process.getErrorStream()) {
                byte[] bytes = new byte[4096];
                int total = 0, n;
                while ((n = in.read(bytes)) != -1) {
                  total += n;
                  if (total > 1048576) {
                    process.destroyForcibly();
                    break;
                  }
                }
              } catch (IOException ignored) {
              }
            });
  }

  public void send(String json) {
    try {
      writer.write(json);
      writer.newLine();
      writer.flush();
    } catch (IOException e) {
      throw ChatGptException.of(TRANSPORT);
    }
  }

  long pid() {
    return process.pid();
  }

  public String poll(Duration timeout) {
    try {
      Object line = lines.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
      return line == null ? "" : line == EOF ? null : (String) line;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw ChatGptException.of(CANCELLED);
    }
  }

  public void close() {
    boolean interrupted = Thread.interrupted();
    // Retain ownership before stdin shutdown can orphan a native helper.
    var owned = new java.util.HashMap<ProcessHandle, java.util.Optional<java.time.Instant>>();
    process.descendants().forEach(p -> owned.put(p, p.info().startInstant()));
    try {
      writer.close();
      if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
        terminate.run();
        if (!process.waitFor(300, TimeUnit.MILLISECONDS)) {
          kill.run();
          process.descendants().forEach(p -> p.destroyForcibly());
          process.destroyForcibly();
          process.waitFor(500, TimeUnit.MILLISECONDS);
        }
      }
    } catch (InterruptedException e) {
      interrupted = true;
      kill.run();
      process.destroyForcibly();
    } catch (Exception ignored) {
      kill.run();
      process.destroyForcibly();
    } finally {
      owned.forEach(
          (p, started) -> {
            if (p.isAlive() && started.isPresent() && started.equals(p.info().startInstant()))
              p.destroyForcibly();
          });
      try {
        CompletableFuture.allOf(
                owned.keySet().stream()
                    .filter(ProcessHandle::isAlive)
                    .map(ProcessHandle::onExit)
                    .toArray(CompletableFuture[]::new))
            .get(500, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        interrupted = true;
      } catch (Exception ignored) {
      }
      cleanup.run();
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
