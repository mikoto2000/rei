package dev.mikoto2000.rei.sound;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SoundNotificationService {

  private static final Logger log = LoggerFactory.getLogger(SoundNotificationService.class);
  private static final String MESSAGE_PLACEHOLDER = "{{MESSAGE}}";
  private static final long TIMEOUT_SECONDS = 300L;

  private final ReentrantLock notificationLock = new ReentrantLock(true);
  private final SoundNotificationProperties properties;

  public void notify(String message) {
    notificationLock.lock();
    try {
      if (!properties.isEnabled()) {
        log.warn("Sound notification is disabled. Falling back to console output.");
        fallbackToConsole(message);
        return;
      }

      if (properties.getCommand().isEmpty()) {
        log.warn("Sound notification command is not configured. Falling back to console output.");
        fallbackToConsole(message);
        return;
      }

      List<String> resolvedCommand = resolveCommand(message);
      executeCommand(resolvedCommand, message);
    } finally {
      notificationLock.unlock();
    }
  }

  private List<String> resolveCommand(String message) {
    boolean hasPlaceholder = properties.getCommand().stream()
        .anyMatch(arg -> arg.contains(MESSAGE_PLACEHOLDER));

    if (!hasPlaceholder) {
      log.warn("Command does not contain {} placeholder; message cannot be passed as an argument.", MESSAGE_PLACEHOLDER);
    }

    return properties.getCommand().stream()
        .map(arg -> arg.replace(MESSAGE_PLACEHOLDER, message))
        .toList();
  }

  private void executeCommand(List<String> command, String message) {
    Process process = null;
    try {
      process = createProcessBuilder(command).start();
      boolean finished = process.waitFor(getTimeoutSeconds(), TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        log.warn("Sound notification command timed out after {} seconds. Falling back to console output.", getTimeoutSeconds());
        fallbackToConsole(message);
        return;
      }
      int exitCode = process.exitValue();
      if (exitCode != 0) {
        log.warn("Sound notification command failed with exit code {}. Falling back to console output.", exitCode);
        fallbackToConsole(message);
      }
    } catch (Exception e) {
      log.warn("Sound notification command failed: {}. Falling back to console output.", e.getMessage());
      if (process != null) {
        process.destroyForcibly();
      }
      fallbackToConsole(message);
    }
  }

  protected ProcessBuilder createProcessBuilder(List<String> command) {
    return new ProcessBuilder(command);
  }

  protected long getTimeoutSeconds() {
    return TIMEOUT_SECONDS;
  }

  private void fallbackToConsole(String message) {
    System.out.println("[notification] " + message);
  }
}
