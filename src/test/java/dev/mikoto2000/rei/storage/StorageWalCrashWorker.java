package dev.mikoto2000.rei.storage;

import java.nio.file.Path;
import java.util.Map;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Deliberately exits without closing SQLite or the Spring context. */
public class StorageWalCrashWorker {
  public static void main(String[] args) {
    var context=new AnnotationConfigApplicationContext();
    context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("worker",Map.of("rei.data-dir",args[0])));
    context.register(StorageMigrationConfiguration.class);context.refresh();
    new StorageDatabase(Path.of(args[0])).transaction(db->{
      try(var sql=db.createStatement()){sql.executeUpdate("INSERT INTO event_sequences VALUES('crash-committed',42)");}
      return null;
    });
    Runtime.getRuntime().halt(0);
  }
}
