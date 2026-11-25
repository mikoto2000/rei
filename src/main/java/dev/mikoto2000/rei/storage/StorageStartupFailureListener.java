package dev.mikoto2000.rei.storage;

import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.context.ApplicationListener;

/** Shows storage lease contention on the launcher's stderr even when logging is file-only. */
public final class StorageStartupFailureListener
    implements ApplicationListener<ApplicationFailedEvent> {
  @Override
  public void onApplicationEvent(ApplicationFailedEvent event) {
    for (Throwable cause = event.getException(); cause != null; cause = cause.getCause()) {
      if (cause instanceof StorageInUseException conflict) {
        System.err.println(conflict.getMessage());
        return;
      }
    }
  }
}
