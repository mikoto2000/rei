package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;

/** Shell-thread history ownership. Switch only between readLine calls, never from event workers. */
public final class ProjectCommandHistory implements AutoCloseable {
  private final LineReaderImpl reader;
  private final Supplier<ProjectContext> selectedProject;
  private final Path directory;
  private Path currentFile;

  public ProjectCommandHistory(
      LineReaderImpl reader, Supplier<ProjectContext> selectedProject, Path dataDirectory)
      throws IOException {
    this.reader = reader;
    this.selectedProject = selectedProject;
    this.directory = dataDirectory.resolve("command-history");
    refresh();
  }

  /** Save the old owner before replacing both its backing file and its in-memory history. */
  public void refresh() throws IOException {
    String id = selectedProject.get().id();
    // Registry identities are UUIDs; validate before using an identity as a filename.
    if (!id.matches("[a-zA-Z0-9_-]+"))
      throw new IOException("Invalid project identity for command history");
    Path nextFile = directory.resolve(id + ".history");
    if (nextFile.equals(currentFile)) return;
    if (currentFile != null) reader.getHistory().save();
    Files.createDirectories(directory);

    var next = new DefaultHistory();
    try {
      // attach() swallows load failures. Attach without a file and load explicitly so a
      // failed switch cannot silently mix histories or overwrite an unreadable file.
      reader.setVariable(LineReader.HISTORY_FILE, null);
      next.attach(reader);
      reader.setVariable(LineReader.HISTORY_FILE, nextFile);
      next.load();
    } catch (IOException | RuntimeException error) {
      reader.setVariable(LineReader.HISTORY_FILE, currentFile);
      throw error;
    }
    reader.setHistory(next);
    currentFile = nextFile;
  }

  @Override
  public void close() throws IOException {
    reader.getHistory().save();
  }
}
