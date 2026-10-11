package dev.mikoto2000.rei.launcher;

/** Real second-JVM lock holder; deliberately never calls prepare/migration. */
public final class BackendLockWorker {
  public static void main(String[] args) throws Exception {
    try (var lease = new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(java.nio.file.Path.of(args[0]))) {
      System.out.println("LOCKED");
      System.out.flush();
      System.in.read();
    } catch (java.io.IOException denied) { System.exit(3); }
  }
}
