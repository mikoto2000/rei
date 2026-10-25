package dev.mikoto2000.rei.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.life.LifeContext;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** 既存 storage.db のトランザクションと起動前移行を共有する。 */
@Component
@DependsOn("storageMigrationLease")
public final class SqliteLifeContextStore implements LifeContext.Store {
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
  private final StorageDatabase database;

  public SqliteLifeContextStore(@Value("${rei.data-dir}") String directory) {
    database = new StorageDatabase(Path.of(directory));
  }

  public LifeContext.Settings load() {
    return database.read(
        db -> {
          try (var q = db.createStatement();
              var r = q.executeQuery("SELECT record FROM life_context WHERE id=1")) {
            return r.next() ? JSON.readValue(r.getString(1), LifeContext.Settings.class) : null;
          }
        });
  }

  public void save(LifeContext.Settings value, long expected) {
    database.transaction(
        db -> {
          try (var q =
              db.prepareStatement(
                  "INSERT INTO life_context(id,revision,record) VALUES(1,?,?) ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,record=excluded.record WHERE life_context.revision=?")) {
            q.setLong(1, value.revision());
            q.setString(2, JSON.writeValueAsString(value));
            q.setLong(3, expected);
            if (q.executeUpdate() != 1)
              throw new IllegalArgumentException("Stale life context revision");
          }
          return null;
        });
  }
}
