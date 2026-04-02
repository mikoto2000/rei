package dev.mikoto2000.rei;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

@Component
public class EscCancellationMonitor {

  private static final int ESC = 27;

  public int await(Future<Integer> future, EscapeReader escapeReader, Runnable cancelAction) throws IOException {
    boolean cancelled = false;
    while (true) {
      try {
        return future.get(50, TimeUnit.MILLISECONDS);
      } catch (TimeoutException e) {
        int ch = escapeReader.read(25);
        if (!cancelled && ch == ESC) {
          cancelled = true;
          cancelAction.run();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("コマンド実行待機が中断されました", e);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof RuntimeException runtimeException) {
          throw runtimeException;
        }
        throw new IllegalStateException("コマンド実行に失敗しました", cause);
      }
    }
  }

  @FunctionalInterface
  public interface EscapeReader {
    int read(long timeoutMillis) throws IOException;
  }
}
