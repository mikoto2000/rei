package dev.mikoto2000.rei.launcher;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;

/** Read-only decision probe. Never claims a lease to hand to a new Backend. */
public final class BackendOwnership {
  private BackendOwnership() {}
  public static boolean isOwned(Path root) throws IOException {
    Path file = root.resolve(".storage/instance.lock");
    if (Files.notExists(file,LinkOption.NOFOLLOW_LINKS)) return false;
    if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
      throw new IOException("Unsafe instance lock path");
    try (var channel = FileChannel.open(file,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)) {
      try (var lock = channel.tryLock()) { return lock == null; }
      catch (OverlappingFileLockException held) { return true; }
    }
  }
}
