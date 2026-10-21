package dev.mikoto2000.rei.slack;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Slackとの連携に必要なSlack通信の接続受付から終了までの管理を扱う。
 *
 * <p>Connection failures are contained to this opt-in channel; initialization never blocks startup.
 */
public final class SlackSocketLifecycle implements SmartLifecycle, AutoCloseable {
  /** Slackとの連携に必要な接続を扱う。 */
  public interface Connection extends AutoCloseable {
    /** Slackとの連携に関するpumpを処理する。 */
    void pump();
  }

  /** 設定。 */
  private final SlackProperties settings;

  /** 実行環境。 */
  private final Map<String, String> environment;

  /** 生成元。 */
  private final Supplier<Connection> factory;

  /** 接続。 */
  private volatile Connection connection;

  /** 実行の担当。 */
  private ScheduledExecutorService worker;

  /** 実行中。 */
  private volatile boolean running;

  /** connecting／終了済み。 */
  private boolean connecting, closed;

  /** 再試行日時。 */
  private long retryAt;

  /** 指定された値や利用先を使って、SlackSocketLifecycleを初期化する。 */
  public SlackSocketLifecycle(
      SlackProperties settings, Map<String, String> environment, Supplier<Connection> factory) {
    this.settings = settings;
    this.environment = environment;
    this.factory = factory;
  }

  /** Slackとの連携において、接続Onceに該当するか判定する。 */
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

  /** 要求された処理を開始する。 */
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

  /** 実行中の処理を停止する。 */
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

  /** Slackとの連携において、実行中かどうかを判定する。 */
  @Override
  public boolean isRunning() {
    return running;
  }

  /** Slackとの連携で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() {
    stop();
  }
}
