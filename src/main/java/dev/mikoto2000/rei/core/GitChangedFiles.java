package dev.mikoto2000.rei.core;

import dev.mikoto2000.rei.core.chat.RunCancellation;
import dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner;
import dev.mikoto2000.rei.externalagent.ExternalAgentResult;
import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 共通の実行基盤に必要なGit変更済みファイル一覧を扱う。
 *
 * <p>Bounded, read-only path inventory. Never reads diff bodies or invokes diff helpers.
 */
final class GitChangedFiles {
  /**
   * 共通の実行基盤で受け渡す観測内容を表す。
   *
   * @param paths パス一覧
   * @param excluded 対象外
   */
  record Observation(List<String> paths, boolean excluded) {}

  /**
   * 共通の実行基盤で受け渡す行一覧を表す。
   *
   * @param ranges 対象範囲一覧
   * @param warnings 注意事項
   */
  record Lines(List<CoverageImpactService.Range> ranges, List<String> warnings) {}

  /** 共通の実行基盤で用いる変更済み行一覧を返す。 */
  Lines changedLines(Path root, Set<String> selected) throws IOException {
    if (selected.isEmpty())
      return new Lines(
          List.of(), List.of("No selected Git paths; no changed-line coverage observations"));
    var args =
        new ArrayList<String>(
            List.of(
                "-c",
                "core.quotePath=false",
                "diff",
                "--no-ext-diff",
                "--no-textconv",
                "--no-renames",
                "--color=never",
                "--unified=0",
                "HEAD",
                "--"));
    selected.stream().sorted().forEach(name -> args.add(":(literal)" + name));
    String output =
        git(
            root,
            System.nanoTime() + Duration.ofSeconds(5).toNanos(),
            1048576,
            args.toArray(String[]::new));
    var ranges = new ArrayList<CoverageImpactService.Range>();
    var warnings = new LinkedHashSet<String>();
    String path = null;
    int total = 0;
    boolean header = false;
    var hunk =
        com.google.re2j.Pattern.compile("^@@ -[0-9]+(?:,[0-9]+)? \\+([0-9]+)(?:,([0-9]+))? @@.*$");
    for (String line : output.split("\n")) {
      RunCancellation.propagate(null);
      if (line.startsWith("diff --git ")) {
        path = null;
        header = true;
      }
      if (header && line.startsWith("+++ ")) {
        String name = line.substring(4);
        path =
            name.startsWith("b/") && selected.contains(name.substring(2))
                ? name.substring(2)
                : null;
        header = false;
      }
      if (line.startsWith("@@")) header = false;
      if (path == null || !line.startsWith("@@")) continue;
      var match = hunk.matcher(line);
      if (!match.matches())
        throw new IOException("Unsupported Git hunk; manual changed lines required");
      try {
        int first = Integer.parseInt(match.group(1)),
            count = match.group(2) == null ? 1 : Integer.parseInt(match.group(2));
        if (count == 0) {
          warnings.add("Deleted lines have no current line coverage; broader regression required");
          continue;
        }
        if (first < 1
            || count > 256
            || first > 1000000 - count + 1
            || ranges.size() >= 64
            || (total += count) > 256)
          throw new IOException("Git changed lines exceed bounds; supply manual selection");
        ranges.add(new CoverageImpactService.Range(path, first, first + count - 1));
      } catch (NumberFormatException invalid) {
        throw new IOException("Git line numbers exceed bounds", invalid);
      }
    }
    warnings.add(
        "Untracked, deleted, binary and staged-only changes may have no current Git hunks; absent line observations are not coverage evidence");
    return new Lines(List.copyOf(ranges), List.copyOf(warnings));
  }

  /** プロセス一覧。 */
  private final ExternalAgentProcessRunner processes;

  /** 指定された値や利用先を使って、GitChangedFilesを初期化する。 */
  GitChangedFiles(ExternalAgentProcessRunner processes) {
    this.processes = processes;
  }

  /** 判断に必要な情報を集め、後続の処理へ渡す。 */
  Observation collect(Path root) throws IOException {
    long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    var identity =
        git(root, deadline, 4096, "rev-parse", "--show-toplevel", "HEAD").lines().toList();
    if (identity.size() != 2
        || !Path.of(identity.getFirst()).toRealPath().equals(root)
        || !identity.getLast().matches("[0-9a-f]{40,64}"))
      throw new IOException("A Git Project root with a HEAD commit is required");
    var paths = new TreeSet<String>();
    add(
        paths,
        git(
            root,
            deadline,
            1048576,
            "diff",
            "--no-ext-diff",
            "--no-textconv",
            "--no-renames",
            "--name-only",
            "-z",
            "--cached",
            "HEAD",
            "--"));
    add(
        paths,
        git(
            root,
            deadline,
            1048576,
            "diff",
            "--no-ext-diff",
            "--no-textconv",
            "--no-renames",
            "--name-only",
            "-z",
            "--"));
    add(paths, git(root, deadline, 1048576, "ls-files", "--others", "--exclude-standard", "-z"));
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (paths.size() > 64)
      throw new IOException("Git changes exceed 64 paths; supply an explicit bounded selection");
    boolean excluded = paths.removeIf(name -> RepositoryMapService.sensitive(Path.of(name)));
    return new Observation(List.copyOf(paths), excluded);
  }

  /** 新たな対象を登録し、後から利用できるようにする。 */
  private static void add(Set<String> paths, String output) throws IOException {
    if (!output.isEmpty() && !output.endsWith("\u0000"))
      throw new IOException("Incomplete Git path inventory");
    for (String name : output.split("\u0000")) {
      if (name.isEmpty()) continue;
      try {
        Path path = Path.of(name);
        // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
        if (name.length() > 1024
            || name.codePoints().anyMatch(Character::isISOControl)
            || path.isAbsolute()
            || path.normalize().startsWith(".."))
          throw new IOException("Unsupported Git change path; manual selection required");
      } catch (InvalidPathException error) {
        throw new IOException("Unsupported Git change path", error);
      }
      paths.add(name);
    }
  }

  /** 共通の実行基盤で用いるGitの情報を返す。 */
  private String git(Path root, long deadline, int maxBytes, String... args) throws IOException {
    RunCancellation.propagate(null);
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new IOException("Git change inventory deadline exceeded");
    Duration timeout = Duration.ofNanos(Math.min(remaining, Duration.ofSeconds(5).toNanos()));
    var command =
        new ArrayList<String>(
            List.of("git", "--no-pager", "--no-optional-locks", "-c", "core.fsmonitor=false"));
    command.addAll(List.of(args));
    var output =
        processes.run(
            command,
            root,
            "",
            timeout,
            timeout,
            maxBytes,
            () -> Thread.currentThread().isInterrupted());
    if (output.status() == ExternalAgentResult.Status.CANCELLED)
      throw new java.util.concurrent.CancellationException("Git change inventory cancelled");
    RunCancellation.propagate(null);
    if (output.status() != ExternalAgentResult.Status.SUCCESS || output.truncated())
      throw new IOException("Git change inventory unavailable or exceeds byte/time limit");
    return output.stdout();
  }
}
