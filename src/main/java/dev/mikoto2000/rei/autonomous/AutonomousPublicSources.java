package dev.mikoto2000.rei.autonomous;

import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.storage.StorageDatabase;
import dev.mikoto2000.rei.storage.StorageObjectRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
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
      insert(db, source);
      return source;
    });
  }

  public Source get(String id) {
    return database
        .read(db -> find(db, id, null))
        .orElseThrow(() -> new NoSuchElementException("Public source not found"));
  }

  public Source version(String id, int revision) {
    return database
        .read(db -> find(db, id, revision))
        .orElseThrow(() -> new NoSuchElementException("Public source not found"));
  }

  /** Editing always removes permission; a separate explicit operation permits the new version. */
  public Source edit(String id, int expectedRevision, String text, String actor) {
    validateRegistration(text, actor);
    return database.transaction(
        db -> replace(db, current(db, id, expectedRevision), text, false, actor));
  }

  public Source permission(String id, int expectedRevision, boolean allowed, String actor) {
    validateActor(actor);
    return database.transaction(db -> {
      var current = current(db, id, expectedRevision);
      validateRegistration(current.text(), actor);
      return replace(db, current, current.text(), allowed, actor);
    });
  }

  private Source replace(Connection db, Source current, String text, boolean allowed, String actor)
      throws SQLException {
    var next =
        new Source(
            current.id(),
            Math.incrementExact(current.revision()),
            text,
            allowed,
            Instant.ofEpochMilli(clock.millis()),
            actor);
    try (var update =
        db.prepareStatement(
            "UPDATE autonomous_public_sources SET is_current=0 WHERE id=? AND revision=? AND is_current=1")) {
      update.setString(1, current.id());
      update.setInt(2, current.revision());
      if (update.executeUpdate() != 1) throw new OperationConflictException();
    }
    insert(db, next);
    return next;
  }

  private static Source current(Connection db, String id, int expectedRevision)
      throws SQLException {
    var source =
        find(db, id, null).orElseThrow(() -> new NoSuchElementException("Public source not found"));
    if (source.revision() != expectedRevision) throw new OperationConflictException();
    return source;
  }

  private static Optional<Source> find(Connection db, String id, Integer revision)
      throws SQLException {
    String predicate = revision == null ? "is_current=1" : "revision=?";
    try (var query =
        db.prepareStatement(
            "SELECT id,revision,body,public_use,updated,actor FROM autonomous_public_sources WHERE id=? AND "
                + predicate)) {
      query.setString(1, id);
      if (revision != null) query.setInt(2, revision);
      try (var rows = query.executeQuery()) {
        if (!rows.next()) return Optional.empty();
        return Optional.of(
            new Source(
                rows.getString("id"),
                rows.getInt("revision"),
                rows.getString("body"),
                rows.getInt("public_use") == 1,
                Instant.ofEpochMilli(rows.getLong("updated")),
                rows.getString("actor")));
      }
    }
  }

  private static void insert(Connection db, Source source) throws SQLException {
    try (var insert =
        db.prepareStatement(
            "INSERT INTO autonomous_public_sources(id,revision,is_current,body,public_use,updated,actor) VALUES(?,?,1,?,?,?,?)")) {
      insert.setString(1, source.id());
      insert.setInt(2, source.revision());
      insert.setString(3, source.text());
      insert.setInt(4, source.publicUseAllowed() ? 1 : 0);
      insert.setLong(5, source.updatedAt().toEpochMilli());
      insert.setString(6, source.actor());
      insert.executeUpdate();
    }
  }

  private static void validateRegistration(String text, String actor) {
    if (text == null
        || text.isBlank()
        || text.length() > 8192
        || !StandardCharsets.UTF_8.newEncoder().canEncode(text)
        || text.getBytes(StandardCharsets.UTF_8).length > 8192)
      throw new IllegalArgumentException("Invalid public source text");
    validateActor(actor);
  }

  private static void validateActor(String actor) {
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
