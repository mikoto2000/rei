package dev.mikoto2000.rei.computer;

import java.util.Optional;

public record ComputerActionResult(
    boolean success,
    Optional<String> failureReason,
    ComputerActionBackend backend,
    boolean fallbackUsed) {

  public ComputerActionResult {
    failureReason = failureReason == null ? Optional.empty() : failureReason;
    backend = backend == null ? ComputerActionBackend.NONE : backend;
  }

  public static ComputerActionResult success(ComputerActionBackend backend, boolean fallbackUsed) {
    return new ComputerActionResult(true, Optional.empty(), backend, fallbackUsed);
  }

  public static ComputerActionResult failure(String reason, ComputerActionBackend backend, boolean fallbackUsed) {
    return new ComputerActionResult(false, Optional.ofNullable(reason), backend, fallbackUsed);
  }
}
