package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.launcher.*;
import java.io.IOException;
import java.util.UUID;

/** Created after the storage gate; public identity is published only at application readiness. */
public final class BackendInstance implements AutoCloseable {
  private final BackendEndpointStore store;
  private final String instanceId = UUID.randomUUID().toString();
  private BackendEndpoint ready;
  public BackendInstance(BackendEndpointStore store) { this.store = store; }
  public synchronized void ready(int port) throws IOException {
    if (ready != null) throw new IllegalStateException("Backend already ready");
    var value = new BackendEndpoint(1,instanceId,store.storageId(),ProcessHandle.current().pid(),
        "http://127.0.0.1:"+port,BackendEndpoint.CURRENT_API_PROTOCOL,"READY");
    store.publish(value);
    ready = value;
  }
  public synchronized BackendEndpoint get() {
    if (ready == null) throw new IllegalStateException("Backend is not ready");
    return ready;
  }
  public synchronized boolean matches(String expected) { return ready != null && instanceId.equals(expected); }
  @Override public synchronized void close() throws IOException {
    if (ready != null) { store.removeOwned(instanceId); ready = null; }
  }
}
