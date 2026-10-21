package dev.mikoto2000.rei.ui.shell;

import dev.mikoto2000.rei.core.project.ProjectContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;
import org.jline.reader.LineReader;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.reader.impl.history.DefaultHistory;

/**
 * ターミナルでの対話に必要なプロジェクトコマンド履歴を扱う。
 *
 * <p>Shell-thread history ownership. Switch only between readLine calls, never from event workers.
 */
public final class ProjectCommandHistory implements AutoCloseable {
  /** 入力。 */
  private final Supplier<LineReaderImpl> input;

  /** 選択中プロジェクト。 */
  private final Supplier<ProjectContext> selectedProject;

  /** ディレクトリ。 */
  private final Path directory;

  /** 現在ファイル。 */
  private Path currentFile;

  /** 指定された値や利用先を使って、ProjectCommandHistoryを初期化する。 */
  public ProjectCommandHistory(
      Supplier<LineReaderImpl> input, Supplier<ProjectContext> selectedProject, Path dataDirectory)
      throws IOException {
    this.input = input;
    this.selectedProject = selectedProject;
    this.directory = dataDirectory.resolve("command-history");
    refresh();
  }

  /**
   * ターミナルでの対話に関する再確認を処理する。
   *
   * <p>Save the old owner before replacing both its backing file and its in-memory history.
   */
  public void refresh() throws IOException {
    var reader = input.get();
    String id = selectedProject.get().id();
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

  /** ターミナルでの対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() throws IOException {
    input.get().getHistory().save();
  }
}
