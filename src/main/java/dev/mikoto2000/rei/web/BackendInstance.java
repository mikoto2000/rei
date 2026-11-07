package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.launcher.BackendEndpoint;
import dev.mikoto2000.rei.launcher.BackendEndpointStore;
import java.io.IOException;
import java.util.UUID;

/**
 * Web APIによる操作に必要なバックエンド起動単位の識別子を扱う。
 *
 * <p>Created after the storage gate; public identity is published only at application readiness.
 */
public final class BackendInstance implements AutoCloseable {
  /** 保存先。 */
  private final PublishEndpoint<BackendEndpoint> publishEndpoint;

  private final RemoveEndpoint removeEndpoint;

  /** 起動単位の識別子識別子。 */
  private final String instanceId = UUID.randomUUID().toString();

  /** 準備完了。 */
  private BackendEndpoint ready;

  /** 指定された値や利用先を使って、BackendInstanceを初期化する。 */
  public BackendInstance(BackendEndpointStore store) {
    this.publishEndpoint =
        port -> {
          var value =
              new BackendEndpoint(
                  1,
                  instanceId,
                  store.storageId(),
                  ProcessHandle.current().pid(),
                  "http://127.0.0.1:" + port,
                  BackendEndpoint.CURRENT_API_PROTOCOL,
                  "READY");
          store.publish(value);
          return value;
        };
    this.removeEndpoint = () -> store.removeOwned(instanceId);
  }

  /** Web APIによる操作に関する準備完了を処理する。 */
  public synchronized void ready(int port) throws IOException {
    if (ready != null) throw new IllegalStateException("Backend already ready");
    ready = publishEndpoint.publish(port);
  }

  /** 指定された対象の情報を、呼び出し元が参照できる形で返す。 */
  public synchronized BackendEndpoint get() {
    if (ready == null) throw new IllegalStateException("Backend is not ready");
    return ready;
  }

  /** 指定された条件と対象が一致するかを判定する。 */
  public synchronized boolean matches(String expected) {
    return ready != null && instanceId.equals(expected);
  }

  /** Web APIによる操作で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public synchronized void close() throws IOException {
    if (ready != null) {
      removeEndpoint.remove();
      ready = null;
    }
  }

  @FunctionalInterface
  private interface PublishEndpoint<T> {
    T publish(int port) throws IOException;
  }

  @FunctionalInterface
  private interface RemoveEndpoint {
    void remove() throws IOException;
  }
}
