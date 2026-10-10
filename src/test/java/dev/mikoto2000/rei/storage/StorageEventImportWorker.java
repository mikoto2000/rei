package dev.mikoto2000.rei.storage;

/** Bounded-heap migration fixture; never reads the entire Event history. */
public final class StorageEventImportWorker {
  public static void main(String[] arguments)throws Exception {
    var root=java.nio.file.Path.of(arguments[0]);
    try(var migration=new StorageMigrationCoordinator(root)){migration.prepare();}
    long count=new StorageDatabase(root).read(db->{try(var query=db.createStatement();var rows=query.executeQuery("SELECT count(*) FROM agent_events")){rows.next();return rows.getLong(1);}});
    var store=new dev.mikoto2000.rei.event.SqliteProjectAgentEventStore(root);
    if(store.readPage(arguments[1],0,false).events().size()!=128)throw new AssertionError("Bounded page missing");
    System.out.println("IMPORTED "+count);
  }
}
