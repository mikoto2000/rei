package dev.mikoto2000.rei.core.completion;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.mikoto2000.rei.core.completion.CompletionEngineTest.context;
import java.nio.file.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilePathCompletionProviderTest {
  @TempDir Path root;
  private final FilePathCompletionProvider provider = new FilePathCompletionProvider();
  @Test void relativePrefixAndSingleFile() throws Exception {
    Files.writeString(root.resolve("report.txt"), "");
    assertThat(provider.complete(context("./rep", "file", root)))
        .extracting(CompletionCandidate::value).containsExactly("./report.txt");
  }
  @Test void absolutePath() throws Exception {
    Path file = Files.createFile(root.resolve("report.txt"));
    assertThat(provider.complete(context(root.resolve("rep").toString(), "file", root)))
        .extracting(CompletionCandidate::value).containsExactly(file.toString());
  }
  @Test void distinguishesTypesAndAppendsDirectorySeparator() throws Exception {
    Files.createDirectory(root.resolve("report dir"));
    Files.createFile(root.resolve("report.txt"));
    assertThat(provider.complete(context("./rep", "file", root)))
        .extracting(CompletionCandidate::value).containsExactly("./report.txt");
    assertThat(provider.complete(context("./rep", "directory", root)))
        .extracting(CompletionCandidate::value).containsExactly("./report dir/");
    assertThat(provider.complete(context("./rep", "file-or-directory", root))).hasSize(2);
    assertThat(provider.complete(context("./rep", "directory", root)).getFirst().appendSpace()).isFalse();
  }
  @Test void noMatchMissingParentAndEmptyDirectory() throws Exception {
    Files.createDirectory(root.resolve("empty"));
    for (String token : new String[]{"absent", "missing/file", "empty/"})
      assertThat(provider.complete(context(token, "file-or-directory", root))).isEmpty();
  }
  @Test void ioFailureIsEmpty() {
    var failing = new FilePathCompletionProvider(path -> { throw new IOException("denied"); }, root, false);
    assertThat(failing.complete(context("./", "file-or-directory", root))).isEmpty();
  }
  @Test void homeExpandsToExecutableAbsolutePath() throws Exception {
    Files.createFile(root.resolve("report.txt"));
    var home = new FilePathCompletionProvider(FilePathCompletionProvider::listDirectory, root,
        java.io.File.separatorChar == '\\');
    assertThat(home.complete(context("~/rep", "file", root)))
        .extracting(CompletionCandidate::value).containsExactly(root.resolve("report.txt").toString().replace('\\', '/'));
  }
  @Test void windowsDriveAndSeparatorsCanBeParsedOnEveryOs() {
    var fragment = PathFragment.parse("F:\\My Documents\\rep", true);
    assertThat(fragment.parent()).isEqualTo("F:\\My Documents\\");
    assertThat(fragment.prefix()).isEqualTo("rep");
    assertThat(fragment.separator()).isEqualTo("\\");
    assertThat(PathFragment.parse("F:/project/do", true).parent()).isEqualTo("F:/project/");
    assertThat(PathFragment.parse("F:\\", true).parent()).isEqualTo("F:\\");
  }
  @Test void unixBackslashIsLiteral() {
    assertThat(PathFragment.parse("/home/my\\file", false).prefix()).isEqualTo("my\\file");
    assertThat(PathFragment.parse("/home/pro", false).parent()).isEqualTo("/home/");
  }
  @Test void onlyListsImmediateChildren() throws Exception {
    Files.createDirectories(root.resolve("sub/nested"));
    Files.createFile(root.resolve("sub/nested/deep.txt"));
    assertThat(provider.complete(context("./", "file-or-directory", root)))
        .extracting(CompletionCandidate::value).containsExactly("./sub/");
  }
  @Test void supportsOnlyDeclaredPathTypes() {
    assertThat(provider.supports(context("", "agent", root))).isFalse();
    assertThat(provider.supports(context("", "directory", root))).isTrue();
  }
  @Test void windowsMatchIsCaseInsensitiveAndUnixMatchIsCaseSensitive() {
    FilePathCompletionProvider.DirectoryLister entries = ignored -> java.util.List.of(new FilePathCompletionProvider.Entry("Report.txt", false));
    assertThat(new FilePathCompletionProvider(entries, root, true).complete(context("rep", "file", root))).hasSize(1);
    assertThat(new FilePathCompletionProvider(entries, root, false).complete(context("rep", "file", root))).isEmpty();
  }
  @Test void uncAndRootLexicalPathsArePreserved() {
    assertThat(PathFragment.parse("\\\\server\\share\\do", true).parent()).isEqualTo("\\\\server\\share\\");
    assertThat(PathFragment.parse("/", false).parent()).isEqualTo("/");
    assertThat(PathFragment.parse("C:docs", true).parent()).isEqualTo("C:");
  }
}
