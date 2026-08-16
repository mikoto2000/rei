package dev.mikoto2000.rei.core.process;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class ManagedBackgroundProcess {
  final String processId;
  final Process process;
  final List<String> commandLine;
  final Path workingDirectory;
  final BoundedLineBuffer stdout;
  final BoundedLineBuffer stderr;
  final AtomicReference<BackgroundProcessStatus> status =
      new AtomicReference<>(BackgroundProcessStatus.STARTING);
  final AtomicReference<Integer> exitCode = new AtomicReference<>();
  final Instant startedAt;
  volatile Instant endedAt;

  ManagedBackgroundProcess(String processId, Process process, List<String> commandLine, Path workingDirectory,
      int logLineCapacity) {
    this.processId = processId;
    this.process = process;
    this.commandLine = List.copyOf(commandLine);
    this.workingDirectory = workingDirectory;
    this.stdout = new BoundedLineBuffer(logLineCapacity);
    this.stderr = new BoundedLineBuffer(logLineCapacity);
    this.startedAt = Instant.now();
  }
}
