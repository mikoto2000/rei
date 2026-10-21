package dev.mikoto2000.rei.voice;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.util.function.LongConsumer;

/** 音声による対話に必要な音声素材通信経路を扱う。 */
@FunctionalInterface
public interface VoiceAssetTransport {
  /** 音声による対話に関する取得を処理する。 */
  void download(
      VoiceModelManifest.Asset asset, Path target, Cancellation cancellation, LongConsumer progress)
      throws IOException;

  /**
   * 音声による対話に必要なキャンセルを扱う。
   *
   * <p>Closing the current HTTP body and interrupting the owner makes cancellation bounded.
   */
  final class Cancellation {
    /** キャンセル済み。 */
    private volatile boolean cancelled;

    /** 本文。 */
    private InputStream body;

    /** 音声による対話で判断に用いるキャンセル済みの条件を確認する。 */
    public boolean cancelled() {
      return cancelled;
    }

    /** 後続の処理を進められるか、現在の条件を確認する。 */
    public void check() throws InterruptedIOException {
      if (cancelled || Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("Voice download cancelled");
    }

    /** 音声による対話に関する関連付けを処理する。 */
    public synchronized void attach(InputStream input) throws IOException {
      if (cancelled) {
        input.close();
        throw new InterruptedIOException("Voice download cancelled");
      }
      body = input;
    }

    /** 音声による対話に関する関連付けの解除を処理する。 */
    public synchronized void detach() {
      body = null;
    }

    /** 対象の実行をキャンセルする。 */
    public void cancel() {
      InputStream input;
      synchronized (this) {
        cancelled = true;
        input = body;
        body = null;
      }
      if (input != null)
        try {
          input.close();
        } catch (IOException ignored) {
        }
    }
  }
}
