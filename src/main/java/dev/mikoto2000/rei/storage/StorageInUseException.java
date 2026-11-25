package dev.mikoto2000.rei.storage;

import java.io.IOException;
import java.nio.file.Path;

/** The storage lease could not be obtained because another instance owns it. */
public final class StorageInUseException extends IOException {
  @java.io.Serial private static final long serialVersionUID = 1L;

  StorageInUseException(Path root, Throwable cause) {
    super("Storage is owned by another application: " + root, cause);
  }
}
