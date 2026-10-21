package dev.mikoto2000.rei.core.completion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** ファイルパス入力補完を用途や設定に応じて提供する窓口。 */
public final class FilePathCompletionProvider implements CompletionProvider {
  /** ファイル／ディレクトリ／BOTH。 */
  public static final String FILE = "file", DIRECTORY = "directory", BOTH = "file-or-directory";

  /**
   * 入力候補の提示で受け渡す項目を表す。
   *
   * @param name 名前
   * @param directory ディレクトリ
   */
  public record Entry(String name, boolean directory) {}

  /** 入力候補の提示に必要なディレクトリListerを扱う。 */
  @FunctionalInterface
  public interface DirectoryLister {
    /** 条件に合う対象を一覧にして、呼び出し元が参照できるようにする。 */
    List<Entry> list(Path parent) throws IOException;
  }

  /** ディレクトリ一覧。 */
  private final DirectoryLister directories;

  /** 利用者のホームディレクトリ。 */
  private final Path home;

  /** 対象環境または判定期間の設定。 */
  private final boolean windows;

  /** 入力候補の提示を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public FilePathCompletionProvider() {
    this(
        FilePathCompletionProvider::listDirectory,
        Path.of(System.getProperty("user.home")),
        java.io.File.separatorChar == '\\');
  }

  /** 指定された値や利用先を使って、FilePathCompletionProviderを初期化する。 */
  public FilePathCompletionProvider(DirectoryLister directories, Path home, boolean windows) {
    this.directories = directories;
    this.home = home;
    this.windows = windows;
  }

  /** 利用可能な対象を確認できるよう、ディレクトリを一覧にする。 */
  public static List<Entry> listDirectory(Path parent) throws IOException {
    try (var children = Files.list(parent)) {
      return children
          .map(path -> new Entry(path.getFileName().toString(), Files.isDirectory(path)))
          .toList();
    }
  }

  /** 入力候補の提示において、ファイルパス入力補完提供元への対応可否を返す。 */
  @Override
  public boolean supports(CompletionContext context) {
    return context.types().stream().anyMatch(type -> Set.of(FILE, DIRECTORY, BOTH).contains(type));
  }

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  @Override
  public List<CompletionCandidate> complete(CompletionContext context) {
    try {
      String token = PathFragment.expandHome(context.currentToken(), home, windows);
      var fragment = PathFragment.parse(token, windows);
      // Drive-relative resolution depends on process state, so only complete rooted drive paths.
      if (windows && fragment.parent().matches("^[a-zA-Z]:$")) return List.of();
      Path parent =
          fragment.parent().isEmpty() ? context.workingDirectory() : Path.of(fragment.parent());
      if (!parent.isAbsolute()) parent = context.workingDirectory().resolve(parent);
      boolean files = context.types().contains(FILE) || context.types().contains(BOTH);
      boolean dirs = context.types().contains(DIRECTORY) || context.types().contains(BOTH);
      return directories.list(parent).stream()
          .filter(entry -> entry.directory() ? dirs : files)
          .filter(entry -> startsWith(entry.name(), fragment.prefix()))
          .sorted(Comparator.comparing(Entry::name))
          .map(
              entry -> {
                String value =
                    fragment.parent()
                        + entry.name()
                        + (entry.directory() ? fragment.separator() : "");
                return new CompletionCandidate(
                    value, value, null, entry.directory() ? DIRECTORY : FILE, !entry.directory());
              })
          .toList();
    } catch (IOException | RuntimeException ignored) {
      return List.of();
    }
  }

  /** 入力候補の提示で判断に用いる開始付きの条件を確認する。 */
  private boolean startsWith(String value, String prefix) {
    return windows
        ? value.regionMatches(true, 0, prefix, 0, prefix.length())
        : value.startsWith(prefix);
  }
}
