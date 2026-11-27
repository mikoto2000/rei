package dev.mikoto2000.rei.autonomous;

import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

/**
 * Explicit post topics. Public snapshots retrieve neither private rows nor registration metadata.
 */
@Component
@DependsOn("storageMigrationLease")
public final class AutonomousPublicSources {
  public record Source(
      String id,
      int revision,
      String text,
      boolean publicUseAllowed,
      Instant updatedAt,
      String actor) {}

  public record PublicSource(String id, String text) {}

  private final StorageDatabase database;
  private final Clock clock;

  @Autowired
  public AutonomousPublicSources(StorageObjectRegistry registry, Clock clock) {
    this(new StorageDatabase(registry.root()), clock);
  }

  public AutonomousPublicSources(StorageDatabase database, Clock clock) {
    this.database = Objects.requireNonNull(database);
    this.clock = Objects.requireNonNull(clock);
  }

  public Source register(String text, boolean publicUseAllowed, String actor) {
    validateRegistration(text, actor);
    return database.transaction(db -> {
      var source =
          new Source(
              UUID.randomUUID().toString(),
              1,
              text,
              publicUseAllowed,
              Instant.ofEpochMilli(clock.millis()),
              actor);
      try (var insert =
          db.prepareStatement(
              "INSERT INTO autonomous_public_sources(id,revision,is_current,body,public_use,updated,actor) VALUES(?,1,1,?,?,?,?)")) {
        insert.setString(1, source.id());
        insert.setString(2, source.text());
        insert.setInt(3, source.publicUseAllowed() ? 1 : 0);
        insert.setLong(4, source.updatedAt().toEpochMilli());
        insert.setString(5, source.actor());
        insert.executeUpdate();
      }
      return source;
    });
  }

  private static void validateRegistration(String text, String actor) {
    if (text == null
        || text.isBlank()
        || text.length() > 8192
        || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
        || text.getBytes(StandardCharsets.UTF_8).length > 8192)
      throw new IllegalArgumentException("Invalid public source text");
    if (actor == null
        || actor.isBlank()
        || actor.length() > 128
        || !StandardCharsets.UTF_8.newEncoder().canEncode(actor)
        || actor.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Invalid source registration actor");
  }

  /** The generator receives bounded, explicitly permitted text and opaque version identifiers. */
  public List<PublicSource> publicSnapshot() {
    return database.read(db -> {
      var result = new ArrayList<PublicSource>();
      int bytes = 0;
      try (var query = db.createStatement();
          var rows =
              query.executeQuery(
                  """
                  SELECT id,revision,body FROM autonomous_public_sources
                  WHERE is_current=1 AND public_use=1 ORDER BY updated DESC,id LIMIT 32
                  """)) {
        while (rows.next()) {
          String text = rows.getString("body");
          int size = text.getBytes(StandardCharsets.UTF_8).length;
          if (bytes + size > 32768) continue;
          bytes += size;
          result.add(
              new PublicSource(
                  "topic:" + rows.getString("id") + ":" + rows.getInt("revision"), text));
        }
      }
      return List.copyOf(result);
    });
  }
}
