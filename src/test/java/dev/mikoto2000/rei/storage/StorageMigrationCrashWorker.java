package dev.mikoto2000.rei.storage;

/** Child JVM fixture: killed inside the schema transaction after DDL, before commit. */
public final class StorageMigrationCrashWorker {
  public static void main(String[] arguments)throws Exception {
    if(arguments.length>1&&arguments[1].equals("hot-journal")) {
      try(var lease=new StorageMigrationCoordinator(java.nio.file.Path.of(arguments[0]));
          var db=java.sql.DriverManager.getConnection("jdbc:sqlite:"+java.nio.file.Path.of(arguments[0]).resolve("storage.db"));var query=db.createStatement()) {
        query.execute("PRAGMA cache_size=10");db.setAutoCommit(false);
        query.execute("CREATE TABLE partial(value BLOB)");query.execute("INSERT INTO partial VALUES(zeroblob(8388608))");
        System.out.println("SPILLED");System.out.flush();System.in.read();
      }
      return;
    }
    try(var coordinator=new StorageMigrationCoordinator(java.nio.file.Path.of(arguments[0]),(stage,backup)->{
      if(stage.name().equals("SCHEMA_WRITTEN")){System.out.println("APPLYING");System.out.flush();System.in.read();}
    })){coordinator.prepare();}
  }
}
