package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Git管理外のディレクトリから、制限付きのファイル一覧を取得する。 */
final class RepositoryFileInventory {
  record Result(List<String> paths, List<String> warnings) {}

  private RepositoryFileInventory() {}

  /** 親のGit管理下にあるサブディレクトリとworktreeもGitの一覧取得を利用する。 */
  static boolean hasGitMetadata(Path root) {
    for (Path parent = root; parent != null; parent = parent.getParent()) {
      if (Files.exists(parent.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) return true;
      if (Files.isRegularFile(parent.resolve("HEAD"), LinkOption.NOFOLLOW_LINKS)
          && Files.isDirectory(parent.resolve("objects"), LinkOption.NOFOLLOW_LINKS)
          && Files.isDirectory(parent.resolve("refs"), LinkOption.NOFOLLOW_LINKS)) return true;
    }
    return false;
  }

  static Result scan(Path root) throws IOException {
    var paths = new ArrayList<String>();
    var warnings = new LinkedHashSet<String>();
    warnings.add(
        "Non-Git directory: bounded filesystem inventory; Git ignore rules are not applied");
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    Files.walkFileTree(
        root,
        Set.of(),
        32,
        new SimpleFileVisitor<>() {
          private int visited;

          private boolean exhausted() {
            RunCancellation.propagate(null);
            if (++visited > 8192 || System.nanoTime() - deadline >= 0) {
              warnings.add("Filesystem inventory entry/time budget exhausted");
              return true;
            }
            return false;
          }

          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (exhausted()) return FileVisitResult.TERMINATE;
            if (!dir.equals(root)
                && (excluded(root.relativize(dir))
                    || Files.isSymbolicLink(dir)
                    || !dir.toRealPath().startsWith(root))) return FileVisitResult.SKIP_SUBTREE;
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (exhausted()) return FileVisitResult.TERMINATE;
            Path relative = root.relativize(file);
            if (excluded(relative)) return FileVisitResult.CONTINUE;
            if (attrs.isDirectory()) {
              warnings.add("Filesystem inventory depth limited to 32 levels");
            } else if (attrs.isRegularFile() && !attrs.isSymbolicLink()) {
              if (paths.size() == 1024) {
                warnings.add("File inventory limited to 1024 entries");
                return FileVisitResult.TERMINATE;
              }
              paths.add(relative.toString().replace('\\', '/'));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException error) throws IOException {
            if (file.equals(root)) throw error;
            warnings.add("Some filesystem inventory paths were unavailable");
            return exhausted() ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException error)
              throws IOException {
            if (dir.equals(root) && error != null) throw error;
            if (error != null) warnings.add("Some filesystem inventory paths were unavailable");
            return FileVisitResult.CONTINUE;
          }
        });
    return new Result(paths.stream().sorted().toList(), List.copyOf(warnings));
  }

  private static boolean excluded(Path relative) {
    if (RepositoryMapService.sensitive(relative)) return true;
    for (Path part : relative) {
      String name = part.toString().toLowerCase(Locale.ROOT);
      if (Set.of(
                  ".tools",
                  ".cache",
                  ".worktrees",
                  ".venv",
                  "venv",
                  ".gradle",
                  "dist",
                  "out",
                  "__pycache__",
                  ".svn",
                  ".hg")
              .contains(name)
          || name.endsWith("-worktrees")) return true;
    }
    return false;
  }
}
