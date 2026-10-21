package dev.mikoto2000.rei.launcher;

/**
 * 検証条件を再現するためのバックエンド排他制御実行の担当。
 *
 * <p>Real second-JVM lock holder; deliberately never calls prepare/migration.
 */
public final class BackendLockWorker {
  /** 検証用に、起動環境の準備の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] args) throws Exception {
    try (var lease =
        new dev.mikoto2000.rei.storage.StorageMigrationCoordinator(
            java.nio.file.Path.of(args[0]))) {
      System.out.println("LOCKED");
      System.out.flush();
      System.in.read();
    } catch (java.io.IOException denied) {
      System.exit(3);
    }
  }
}
