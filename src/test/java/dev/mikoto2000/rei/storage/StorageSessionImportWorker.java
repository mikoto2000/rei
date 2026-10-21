package dev.mikoto2000.rei.storage;

/** 検証条件を再現するための保存会話取り込み実行の担当。 */
public final class StorageSessionImportWorker {
  /** 検証用に、データ保存の共通処理の起動要求を受け付け、実行を開始する。 */
  public static void main(String[] arguments) throws Exception {
    var root = java.nio.file.Path.of(arguments[0]);
    try (var migration = new StorageMigrationCoordinator(root)) {
      migration.prepare();
    }
    long count =
        new StorageDatabase(root)
            .read(
                connection -> {
                  try (var query = connection.createStatement();
                      var rows = query.executeQuery("SELECT count(*) FROM sessions")) {
                    rows.next();
                    return rows.getLong(1);
                  }
                });
    if (new dev.mikoto2000.rei.conversation.SqliteSessionRepository(root)
            .findPage(null, null, 101)
            .size()
        != 101) throw new AssertionError("Bounded page missing");
    System.out.println("IMPORTED " + count);
  }
}
