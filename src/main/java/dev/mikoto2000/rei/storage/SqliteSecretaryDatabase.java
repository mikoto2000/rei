package dev.mikoto2000.rei.storage;

import dev.mikoto2000.rei.secretary.SecretaryDatabase;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/** Adapter to the existing serialized, transactional, migrated storage database. */
@Component
@DependsOn("storageMigrationLease")
@ConditionalOnProperty(name = "rei.secretary.enabled", havingValue = "true")
public final class SqliteSecretaryDatabase implements SecretaryDatabase {
  private final StorageDatabase database;

  public SqliteSecretaryDatabase(@Value("${rei.data-dir}") String directory) {
    database = new StorageDatabase(Path.of(directory));
  }

  @Override
  public <T> T read(Work<T> work) {
    return database.read(work::run);
  }

  @Override
  public <T> T transaction(Work<T> work) {
    return database.transaction(work::run);
  }
}
