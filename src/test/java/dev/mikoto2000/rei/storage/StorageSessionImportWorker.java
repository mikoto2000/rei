package dev.mikoto2000.rei.storage;

public final class StorageSessionImportWorker {
  public static void main(String[] arguments)throws Exception {
    var root=java.nio.file.Path.of(arguments[0]);
    try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}
    long count=new StorageDatabase(root).read(connection->{try(var query=connection.createStatement();var rows=query.executeQuery("SELECT count(*) FROM sessions")){rows.next();return rows.getLong(1);}});
    if(new dev.mikoto2000.rei.conversation.SqliteSessionRepository(root).findPage(null,null,101).size()!=101)throw new AssertionError("Bounded page missing");
    System.out.println("IMPORTED "+count);
  }
}
