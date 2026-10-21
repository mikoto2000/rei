package dev.mikoto2000.rei;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** れいの起動に必要なEscキャンセル排他制御を扱う。 */
@Component
public class EscCancellationMonitor {

  /** 端末の装飾を開始する制御文字。 */
  private static final int ESC = 27;

  /** 必要な状態が整ってから先へ進むため、Escキャンセル排他制御を待つ。 */
  public int await(Future<Integer> future, EscapeReader escapeReader, Runnable cancelAction)
      throws IOException {
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
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
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

  /** れいの起動に必要な特殊文字の保護読み取り元を扱う。 */
  @FunctionalInterface
  public interface EscapeReader {
    /** 指定された対象の内容を読み取り、後続の処理で参照できるようにする。 */
    int read(long timeoutMillis) throws IOException;
  }
}
