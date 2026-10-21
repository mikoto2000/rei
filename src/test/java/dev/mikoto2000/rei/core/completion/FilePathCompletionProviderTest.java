package dev.mikoto2000.rei.core.completion;

import static dev.mikoto2000.rei.core.completion.CompletionEngineTest.context;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ファイルパス完了提供元の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class FilePathCompletionProviderTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 検証に使う提供元。 */
  private final FilePathCompletionProvider provider = new FilePathCompletionProvider();

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void relativePrefixAndSingleFile() throws Exception {
    Files.writeString(root.resolve("report.txt"), "");
    assertThat(provider.complete(context("./rep", "file", root)))
        .extracting(CompletionCandidate::value)
        .containsExactly("./report.txt");
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void absolutePath() throws Exception {
    Path file = Files.createFile(root.resolve("report.txt"));
    assertThat(provider.complete(context(root.resolve("rep").toString(), "file", root)))
        .extracting(CompletionCandidate::value)
        .containsExactly(file.toString());
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void distinguishesTypesAndAppendsDirectorySeparator() throws Exception {
    Files.createDirectory(root.resolve("report dir"));
    Files.createFile(root.resolve("report.txt"));
    assertThat(provider.complete(context("./rep", "file", root)))
        .extracting(CompletionCandidate::value)
        .containsExactly("./report.txt");
    assertThat(provider.complete(context("./rep", "directory", root)))
        .extracting(CompletionCandidate::value)
        .containsExactly("./report dir/");
    assertThat(provider.complete(context("./rep", "file-or-directory", root))).hasSize(2);
    assertThat(provider.complete(context("./rep", "directory", root)).getFirst().appendSpace())
        .isFalse();
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void noMatchMissingParentAndEmptyDirectory() throws Exception {
    Files.createDirectory(root.resolve("empty"));
    for (String token : new String[] {"absent", "missing/file", "empty/"})
      assertThat(provider.complete(context(token, "file-or-directory", root))).isEmpty();
  }

  /** 入力候補の提示で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void ioFailureIsEmpty() {
    var failing =
        new FilePathCompletionProvider(
            path -> {
              throw new IOException("denied");
            },
            root,
            false);
    assertThat(failing.complete(context("./", "file-or-directory", root))).isEmpty();
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void homeExpandsToExecutableAbsolutePath() throws Exception {
    Files.createFile(root.resolve("report.txt"));
    var home =
        new FilePathCompletionProvider(
            FilePathCompletionProvider::listDirectory, root, java.io.File.separatorChar == '\\');
    assertThat(home.complete(context("~/rep", "file", root)))
        .extracting(CompletionCandidate::value)
        .containsExactly(root.resolve("report.txt").toString().replace('\\', '/'));
  }

  /** 入力候補の提示で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void windowsDriveAndSeparatorsCanBeParsedOnEveryOs() {
    var fragment = PathFragment.parse("F:\\My Documents\\rep", true);
    assertThat(fragment.parent()).isEqualTo("F:\\My Documents\\");
    assertThat(fragment.prefix()).isEqualTo("rep");
    assertThat(fragment.separator()).isEqualTo("\\");
    assertThat(PathFragment.parse("F:/project/do", true).parent()).isEqualTo("F:/project/");
    assertThat(PathFragment.parse("F:\\", true).parent()).isEqualTo("F:\\");
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void unixBackslashIsLiteral() {
    assertThat(PathFragment.parse("/home/my\\file", false).prefix()).isEqualTo("my\\file");
    assertThat(PathFragment.parse("/home/pro", false).parent()).isEqualTo("/home/");
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void onlyListsImmediateChildren() throws Exception {
    Files.createDirectories(root.resolve("sub/nested"));
    Files.createFile(root.resolve("sub/nested/deep.txt"));
    assertThat(provider.complete(context("./", "file-or-directory", root)))
        .extracting(CompletionCandidate::value)
        .containsExactly("./sub/");
  }

  /** 入力候補の提示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void supportsOnlyDeclaredPathTypes() {
    assertThat(provider.supports(context("", "agent", root))).isFalse();
    assertThat(provider.supports(context("", "directory", root))).isTrue();
  }

  /** 入力候補の提示で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void windowsMatchIsCaseInsensitiveAndUnixMatchIsCaseSensitive() {
    FilePathCompletionProvider.DirectoryLister entries =
        ignored -> java.util.List.of(new FilePathCompletionProvider.Entry("Report.txt", false));
    assertThat(
            new FilePathCompletionProvider(entries, root, true)
                .complete(context("rep", "file", root)))
        .hasSize(1);
    assertThat(
            new FilePathCompletionProvider(entries, root, false)
                .complete(context("rep", "file", root)))
        .isEmpty();
  }

  /** 入力候補の提示で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void uncAndRootLexicalPathsArePreserved() {
    assertThat(PathFragment.parse("\\\\server\\share\\do", true).parent())
        .isEqualTo("\\\\server\\share\\");
    assertThat(PathFragment.parse("/", false).parent()).isEqualTo("/");
    assertThat(PathFragment.parse("C:docs", true).parent()).isEqualTo("C:");
  }
}
