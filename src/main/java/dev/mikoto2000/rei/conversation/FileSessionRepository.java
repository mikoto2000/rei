package dev.mikoto2000.rei.conversation;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mikoto2000.rei.application.session.*;

/** One application writer, atomic replacement, and monitor-protected admission/read operations. */
public final class FileSessionRepository implements SessionRepository {
  private final Path file;
  private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
  public FileSessionRepository(Path file) { this.file = file.toAbsolutePath().normalize(); }

  @Override public synchronized Optional<SessionMetadata> findById(String id) {
    return read().stream().filter(row -> row.sessionId().equals(id)).findFirst();
  }

  @Override public synchronized List<SessionMetadata> findPage(String projectId, CursorKey after, int fetchLimit) {
    if (fetchLimit < 1 || fetchLimit > 101) throw new IllegalArgumentException("Invalid fetch limit");
    return read().stream().filter(row -> projectId == null || row.projectId().equals(projectId))
        .filter(row -> after == null || row.updatedAt().isBefore(after.time())
            || row.updatedAt().equals(after.time()) && row.sessionId().compareTo(after.id()) > 0)
        .sorted(Comparator.comparing(SessionMetadata::updatedAt).reversed().thenComparing(SessionMetadata::sessionId))
        .limit(fetchLimit).toList();
  }

  @Override public synchronized void accept(SessionMetadata metadata, Runnable enqueue) {
    var previous = read();
    var next = new ArrayList<>(previous);
    var existing = previous.stream().filter(row -> row.sessionId().equals(metadata.sessionId())).findFirst();
    if (existing.isPresent()) {
      var row = existing.get();
      if (!row.projectId().equals(metadata.projectId()) || !row.title().equals(metadata.title())
          || !row.createdAt().equals(metadata.createdAt())) throw new IllegalArgumentException("Immutable session metadata");
      next.set(next.indexOf(row), row.touched(metadata.updatedAt()));
    } else next.add(metadata);
    save(next);
    try { enqueue.run(); }
    catch (RuntimeException | Error error) {
      try { save(previous); } catch (RuntimeException rollback) { error.addSuppressed(rollback); }
      throw error;
    }
  }

  private List<SessionMetadata> read() {
    if (!Files.exists(file)) return List.of();
    try { return List.of(mapper.readValue(Files.readString(file), SessionMetadata[].class)); }
    catch (IOException error) { throw new IllegalStateException("Cannot read session metadata", error); }
  }

  private void save(List<SessionMetadata> rows) {
    Path temporary = null;
    try {
      Files.createDirectories(file.getParent());
      temporary = Files.createTempFile(file.getParent(), "sessions-", ".tmp");
      Files.writeString(temporary, mapper.writeValueAsString(rows));
      try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException error) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    } catch (IOException error) { throw new IllegalStateException("Cannot persist session metadata", error); }
    finally {
      if (temporary != null) try { Files.deleteIfExists(temporary); }
      catch (IOException error) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot remove session temporary file", error); }
    }
  }
}
