package dev.mikoto2000.rei.core.process;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.SystemShellService;
import jakarta.annotation.PreDestroy;

@Component
public class BackgroundProcessManager {
  private static final int LOG_LINE_CAPACITY = 200;
  private static final int DEFAULT_TAIL_LINES = 100;

  private final SystemShellService systemShellService;
  private final ConcurrentMap<String, ManagedBackgroundProcess> processes = new ConcurrentHashMap<>();
  private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
    Thread thread = new Thread(r, "rei-background-process");
    thread.setDaemon(true);
    return thread;
  });

  @Autowired
  public BackgroundProcessManager(SystemShellService systemShellService) {
    this.systemShellService = systemShellService;
  }

  public BackgroundProcessSnapshot spawnShell(String command, Path workingDirectory) {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command は空にできません");
    }
    String shell = systemShellService.resolveShell(System.getenv(), System.getProperty("os.name"));
    return spawnCommandLine(systemShellService.shellCommandLine(shell, command), workingDirectory);
  }

  BackgroundProcessSnapshot spawnCommandLine(List<String> commandLine, Path workingDirectory) {
    String processId = "proc-" + UUID.randomUUID().toString().substring(0, 8);
    try {
      Process process = new ProcessBuilder(commandLine)
          .directory(workingDirectory.toFile())
          .start();
      ManagedBackgroundProcess managedProcess = new ManagedBackgroundProcess(
          processId, process, commandLine, workingDirectory, LOG_LINE_CAPACITY);
      processes.put(processId, managedProcess);
      managedProcess.status.set(BackgroundProcessStatus.RUNNING);
      executor.submit(() -> readLines(process.getInputStream(), managedProcess.stdout));
      executor.submit(() -> readLines(process.getErrorStream(), managedProcess.stderr));
      CompletableFuture.runAsync(() -> watchProcess(managedProcess), executor);
      return snapshot(managedProcess, DEFAULT_TAIL_LINES, "started");
    } catch (IOException e) {
      return new BackgroundProcessSnapshot(processId, -1, BackgroundProcessStatus.FAILED, null, null, Instant.now(),
          List.of(), List.of(), false, e.getMessage());
    }
  }

  public BackgroundProcessSnapshot status(String processId, Integer tailLines) {
    ManagedBackgroundProcess managedProcess = processes.get(processId);
    if (managedProcess == null) {
      return new BackgroundProcessSnapshot(processId, -1, BackgroundProcessStatus.FAILED, null, null, null,
          List.of(), List.of(), false, "process not found");
    }
    return snapshot(managedProcess, normalizeTailLines(tailLines), "ok");
  }

  @PreDestroy
  public void shutdown() {
    processes.values().forEach(managedProcess -> {
      if (managedProcess.process.isAlive()) {
        managedProcess.status.set(BackgroundProcessStatus.KILLED);
        managedProcess.process.destroyForcibly();
      }
    });
    executor.shutdownNow();
  }

  private void watchProcess(ManagedBackgroundProcess managedProcess) {
    try {
      int exitCode = managedProcess.process.waitFor();
      managedProcess.exitCode.set(exitCode);
      managedProcess.endedAt = Instant.now();
      managedProcess.status.compareAndSet(BackgroundProcessStatus.RUNNING, BackgroundProcessStatus.EXITED);
      managedProcess.status.compareAndSet(BackgroundProcessStatus.STARTING, BackgroundProcessStatus.EXITED);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void readLines(InputStream stream, BoundedLineBuffer buffer) {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        buffer.add(line);
      }
    } catch (IOException e) {
      buffer.add("[read error] " + e.getMessage());
    }
  }

  private BackgroundProcessSnapshot snapshot(ManagedBackgroundProcess managedProcess, int tailLines, String message) {
    return new BackgroundProcessSnapshot(
        managedProcess.processId,
        managedProcess.process.pid(),
        managedProcess.status.get(),
        managedProcess.exitCode.get(),
        managedProcess.startedAt,
        managedProcess.endedAt,
        managedProcess.stdout.tail(tailLines),
        managedProcess.stderr.tail(tailLines),
        true,
        message);
  }

  private int normalizeTailLines(Integer tailLines) {
    if (tailLines == null || tailLines <= 0) {
      return DEFAULT_TAIL_LINES;
    }
    return Math.min(tailLines, LOG_LINE_CAPACITY);
  }
}
