package dev.mikoto2000.rei.chatgpt;

import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.CANCELLED;
import static dev.mikoto2000.rei.chatgpt.ChatGptException.Code.TRANSPORT;

import java.io.BufferedInputStream;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * ChatGPTとの連携に必要なアプリケーションサーバープロセス会話を扱う。
 *
 * <p>Bounded stdio and owned-process cleanup shared by both launchers.
 */
final class AppServerProcessSession implements PlanAppServerRpc.Session {
  /** 受信データの終わりを示す値。 */
  private static final Object EOF = new Object();

  /** 行一覧。 */
  private final BlockingQueue<Object> lines = new ArrayBlockingQueue<>(64);

  /** プロセス。 */
  private final Process process;

  /** 終了／強制終了／後片付け。 */
  private final Runnable terminate, kill, cleanup;

  /** 書き込み先。 */
  private final BufferedWriter writer;

  /** 指定された値や利用先を使って、AppServerProcessSessionを初期化する。 */
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

  /** ChatGPTとの連携に関する送信を処理する。 */
  public void send(String json) {
    try {
      writer.write(json);
      writer.newLine();
      writer.flush();
    } catch (IOException e) {
      throw ChatGptException.of(TRANSPORT);
    }
  }

  /** ChatGPTとの連携で用いるプロセス識別子を返す。 */
  long pid() {
    return process.pid();
  }

  /** ChatGPTとの連携で用いる状態確認を返す。 */
  public String poll(Duration timeout) {
    try {
      Object line = lines.poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
      return line == null ? "" : line == EOF ? null : (String) line;
    } catch (InterruptedException e) {
      // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
      Thread.currentThread().interrupt();
      throw ChatGptException.of(CANCELLED);
    }
  }

  /** ChatGPTとの連携で利用した資源や登録を解放し、利用を終了する。 */
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
