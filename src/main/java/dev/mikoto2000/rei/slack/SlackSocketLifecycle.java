package dev.mikoto2000.rei.slack;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Connection failures are contained to this opt-in channel; initialization never blocks startup.
 */
public final class SlackSocketLifecycle implements SmartLifecycle, AutoCloseable {
  public interface Connection extends AutoCloseable {
    void pump();
  }

  private final SlackProperties settings;
  private final Map<String, String> environment;
  private final Supplier<Connection> factory;
  private volatile Connection connection;
  private ScheduledExecutorService worker;
  private volatile boolean running;
  private boolean connecting, closed;
  private long retryAt;

  public SlackSocketLifecycle(
      SlackProperties settings, Map<String, String> environment, Supplier<Connection> factory) {
    this.settings = settings;
    this.environment = environment;
    this.factory = factory;
  }

  boolean connectOnce() {
    synchronized (this) {
      if (connection != null) return true;
      if (closed || connecting || !settings.isEnabled()) return false;
      if (environment.getOrDefault("SLACK_BOT_TOKEN", "").isBlank()
          || environment.getOrDefault("SLACK_APP_TOKEN", "").isBlank()) {
        LoggerFactory.getLogger(getClass())
            .warn("Slack disabled: SLACK_BOT_TOKEN and SLACK_APP_TOKEN are required");
        return false;
      }
      connecting = true;
    }
    try {
      var created = factory.get();
      synchronized (this) {
        if (closed) {
          if (created != null)
            try {
              created.close();
            } catch (Exception ignored) {
            }
          return false;
        }
        connection = created;
        return created != null;
      }
    } catch (RuntimeException error) {
      LoggerFactory.getLogger(getClass())
          .warn(
              "Slack connection unavailable: {}; retry pending (credentials and error details omitted)",
              error instanceof SlackSetupException setup ? setup.code.name() : "CONNECTION_FAILED");
      return false;
    } finally {
      synchronized (this) {
        connecting = false;
      }
    }
  }

  @Override
  public synchronized void start() {
    if (running || !settings.isEnabled()) return;
    closed = false;
    running = true;
    worker =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("rei-slack").factory());
    worker.scheduleWithFixedDelay(
        () -> {
          try {
            if (!running) return;
            if (connection == null && System.currentTimeMillis() >= retryAt) {
              if (!connectOnce()) retryAt = System.currentTimeMillis() + 30_000;
            }
            var current = connection;
            if (current != null && running) current.pump();
          } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(getClass())
                .warn(
                    "Slack processing unavailable; durable receipts retained (error details omitted)");
          }
        },
        0,
        1,
        TimeUnit.SECONDS);
  }

  @Override
  public synchronized void stop() {
    closed = true;
    running = false;
    if (worker != null) {
      worker.shutdownNow();
      worker = null;
    }
    if (connection != null) {
      try {
        connection.close();
      } catch (Exception ignored) {
        LoggerFactory.getLogger(getClass())
            .warn("Slack shutdown incomplete (error details omitted)");
      } finally {
        connection = null;
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public void close() {
    stop();
  }
}
