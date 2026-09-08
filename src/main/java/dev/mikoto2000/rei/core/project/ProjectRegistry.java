package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Small, atomic JSON registry. Failed writes leave both the file and published state unchanged. */
public final class ProjectRegistry {
  private final Path file;
  private final ObjectMapper mapper = new ObjectMapper();
  public ProjectRegistry(Path file) { this.file = file; }
  public record Entry(String id, String name, String path) {
    ProjectContext context() { return new ProjectContext(id, name, Path.of(path)); }
  }
  public record Document(List<Entry> projects) {}

  public synchronized ProjectContext resolve(Path path) {
    Path canonical = canonical(path);
    var entries = read();
    for (var entry : entries) if (Path.of(entry.path()).equals(canonical)) return entry.context();
    var entry = new Entry(UUID.randomUUID().toString(), canonical.getFileName() == null ? canonical.toString()
        : canonical.getFileName().toString(), canonical.toString());
    entries.add(entry);
    save(entries);
    return entry.context();
  }
  public synchronized List<ProjectContext> list() { return read().stream().map(Entry::context).toList(); }
  public synchronized void relocate(String id, Path path) {
    Path canonical = canonical(path);
    var entries = read();
    if (entries.stream().anyMatch(e -> !e.id().equals(id) && e.path().equals(canonical.toString())))
      throw new IllegalArgumentException("Project location already registered");
    boolean found = false;
    for (int i = 0; i < entries.size(); i++) if (entries.get(i).id().equals(id)) {
      entries.set(i, new Entry(id, entries.get(i).name(), canonical.toString())); found = true;
    }
    if (!found) throw new IllegalArgumentException("Unknown project: " + id);
    save(entries);
  }
  public synchronized void remove(Path path) {
    var entries = read();
    entries.removeIf(e -> Path.of(e.path()).equals(path.toAbsolutePath().normalize()));
    save(entries);
  }
  private List<Entry> read() {
    if (!Files.exists(file)) return new ArrayList<>();
    try { return new ArrayList<>(mapper.readValue(Files.readString(file), Document.class).projects()); }
    catch (IOException e) { throw new IllegalStateException("Cannot read project registry: " + file, e); }
  }
  private void save(List<Entry> entries) {
    Path temporary = null;
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "projects-", ".tmp");
      Files.writeString(temporary, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(new Document(entries)));
      try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
      catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
    } catch (IOException e) { throw new IllegalStateException("Cannot save project registry: " + file, e); }
    finally {
      if (temporary != null) try { Files.deleteIfExists(temporary); }
      catch (IOException e) { org.slf4j.LoggerFactory.getLogger(ProjectRegistry.class).warn("Cannot remove registry temporary file", e); }
    }
  }
  private static Path canonical(Path path) {
    try {
      Path canonical = path.toRealPath();
      if (!Files.isDirectory(canonical)) throw new IllegalArgumentException("Not a directory: " + path);
      return canonical;
    } catch (IOException e) { throw new IllegalArgumentException("Cannot resolve project: " + path, e); }
  }
}
