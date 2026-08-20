package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.annotation.Tool;

import dev.mikoto2000.rei.core.process.BackgroundProcessSnapshot;
import dev.mikoto2000.rei.core.process.BackgroundProcessStatus;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.SystemShellService;

class ToolsTest {

  @TempDir
  Path tempDir;

  @Test
  void readPdfFileExtractsBodyText() throws IOException {
    Path pdf = tempDir.resolve("sample.pdf");
    writePdf(pdf, "PDF reading test");

    Tools tools = new Tools();
    String actual = tools.readPdfFile(pdf.toString());

    assertTrue(actual.contains("PDF reading test"));
  }

  @Test
  void legacySingleOperationToolsAreNotExposed() {
    List<String> toolNames = java.util.Arrays.stream(Tools.class.getDeclaredMethods())
        .map(method -> method.getAnnotation(Tool.class))
        .filter(annotation -> annotation != null)
        .map(Tool::name)
        .toList();

    assertFalse(toolNames.contains("grep"));
    assertFalse(toolNames.contains("readTextFile"));
    assertFalse(toolNames.contains("readTextFileRange"));
    assertFalse(toolNames.contains("writeTextFile"));
    assertTrue(toolNames.contains("grepMultiQuery"));
    assertTrue(toolNames.contains("readMultiFile"));
    assertTrue(toolNames.contains("writeMultiFile"));
  }

  @Test
  void gitLsFilesIncludesTrackedAndUntrackedButExcludesIgnored() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/tracked.txt"), "tracked");
    Files.writeString(tempDir.resolve("docs/untracked.txt"), "untracked");
    Files.writeString(tempDir.resolve(".gitignore"), "docs/ignored.txt\n");
    Files.writeString(tempDir.resolve("docs/ignored.txt"), "ignored");
    runGit("add", ".gitignore", "docs/tracked.txt");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<String> files = tools.gitLsFiles(List.of("docs"), tempDir);

    assertTrue(files.contains("docs/tracked.txt"));
    assertTrue(files.contains("docs/untracked.txt"));
    assertFalse(files.contains("docs/ignored.txt"));
  }

  @Test
  void findFileMatchesUntrackedFiles() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/found-me.txt"), "untracked");

    Tools tools = new Tools();
    List<String> files = tools.findFile("found-me.txt", tempDir);

    assertTrue(files.contains("docs/found-me.txt"));
  }

  @Test
  void listFileIncludesUntrackedFiles() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/tracked.txt"), "tracked");
    Files.writeString(tempDir.resolve("docs/untracked.txt"), "untracked");
    runGit("add", "docs/tracked.txt");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<String> files = tools.listFile("docs", tempDir);

    assertTrue(files.contains("docs/tracked.txt"));
    assertTrue(files.contains("docs/untracked.txt"));
  }

  @Test
  void listFileReturnsEmptyWhenBaseDirectoryDoesNotExistWithoutGit() throws Exception {
    Tools tools = new Tools();

    List<String> files = tools.listFile("missing", tempDir);

    assertEquals(List.of(), files);
  }

  @Test
  void grepFindsMatchesInTrackedAndUntrackedFiles() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/tracked.txt"), "hello spring ai");
    Files.writeString(tempDir.resolve("docs/untracked.txt"), "spring tools");
    Files.writeString(tempDir.resolve(".gitignore"), "docs/ignored.txt\n");
    Files.writeString(tempDir.resolve("docs/ignored.txt"), "spring should be ignored");
    runGit("add", ".gitignore", "docs/tracked.txt");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<String> results = tools.grep("spring", "docs", tempDir);

    assertTrue(results.stream().anyMatch(line -> line.startsWith("docs/tracked.txt:1:")));
    assertTrue(results.stream().anyMatch(line -> line.startsWith("docs/untracked.txt:1:")));
    assertFalse(results.stream().anyMatch(line -> line.contains("ignored.txt")));
  }

  @Test
  void grepSupportsIgnoreCaseAndFixedString() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/note.txt"), "Hello [Spring]\nregex spring\n");

    Tools tools = new Tools();
    List<String> results = tools.grep("[spring]", "docs", true, true, false, false, 0, 0, 100, true, tempDir);

    assertEquals(List.of("docs/note.txt:1:Hello [Spring]"), results);
  }

  @Test
  void grepSupportsFileNamesOnly() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/first.txt"), "spring\nspring\n");
    Files.writeString(tempDir.resolve("docs/second.txt"), "spring\n");

    Tools tools = new Tools();
    List<String> results = tools.grep("spring", "docs", false, false, false, true, 0, 0, 100, true, tempDir);

    assertEquals(List.of("docs/first.txt", "docs/second.txt"), results);
  }

  @Test
  void grepSupportsInvertMatchAndContext() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/note.txt"), "alpha\nbeta\ngamma\n");

    Tools tools = new Tools();
    List<String> results = tools.grep("beta", "docs", false, false, true, false, 1, 1, 100, true, tempDir);

    assertEquals(List.of(
        "docs/note.txt:1:alpha",
        "docs/note.txt-2-beta",
        "docs/note.txt:3:gamma"), results);
  }

  @Test
  void grepSupportsIncludeGlob() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/App.java"), "class App { String value = \"spring\"; }\n");
    Files.writeString(tempDir.resolve("docs/note.txt"), "spring in text\n");

    Tools tools = new Tools();
    List<String> results = tools.grep("spring", "docs", false, false, false, false, 0, 0, 100, true,
        "**/*.java", null, tempDir);

    assertEquals(List.of("docs/App.java:1:class App { String value = \"spring\"; }"), results);
  }

  @Test
  void grepSupportsExcludeGlob() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs").resolve("target"));
    Files.writeString(tempDir.resolve("docs/App.java"), "spring source\n");
    Files.writeString(tempDir.resolve("docs/target/Generated.java"), "spring generated\n");

    Tools tools = new Tools();
    List<String> results = tools.grep("spring", "docs", false, false, false, false, 0, 0, 100, true,
        "**/*.java", "**/target/**", tempDir);

    assertEquals(List.of("docs/App.java:1:spring source"), results);
  }

  @Test
  void grepRejectsInvalidRegex() throws Exception {
    Tools tools = new Tools();

    assertThrows(IllegalArgumentException.class,
        () -> tools.grep("[", "docs", false, false, false, false, 0, 0, 100, true, tempDir));
  }

  @Test
  void grepMultiQueryExecutesSingleQuery() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/tracked.txt"), "hello spring ai");
    runGit("add", "docs/tracked.txt");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("spring", "docs", null, null, null, null, null, null, null, null, null, null)
    ), tempDir);

    assertEquals(1, results.size());
    assertEquals(0, results.get(0).queryIndex());
    assertEquals("spring", results.get(0).pattern());
    assertEquals(1, results.get(0).matches().size());
    assertEquals("docs/tracked.txt", results.get(0).matches().get(0).path());
    assertEquals(1, results.get(0).matches().get(0).line());
  }

  @Test
  void grepMultiQueryExecutesMultipleQueriesAndPreservesOrder() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/a.txt"), "foo\nbar\n");
    Files.writeString(tempDir.resolve("docs/b.txt"), "baz\n");
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("foo", "docs", null, null, null, null, null, null, null, null, null, null),
        new Tools.GrepQuery("baz", "docs", null, null, null, null, null, null, null, null, null, null)
    ), tempDir);

    assertEquals(2, results.size());
    assertEquals(0, results.get(0).queryIndex());
    assertEquals("foo", results.get(0).pattern());
    assertEquals(1, results.get(0).matches().size());
    assertEquals("docs/a.txt", results.get(0).matches().get(0).path());
    assertEquals(1, results.get(1).queryIndex());
    assertEquals("baz", results.get(1).pattern());
    assertEquals("docs/b.txt", results.get(1).matches().get(0).path());
  }

  @Test
  void grepMultiQueryReturnsZeroMatchesForNoHit() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/a.txt"), "foo\n");
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("missing", "docs", null, null, null, null, null, null, null, null, null, null)
    ), tempDir);

    assertEquals(1, results.size());
    assertEquals(0, results.get(0).matches().size());
    assertEquals(null, results.get(0).error());
  }

  @Test
  void grepMultiQuerySameFileHitByMultipleQueries() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/a.txt"), "alpha beta\n");
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("alpha", "docs", null, null, null, null, null, null, null, null, null, null),
        new Tools.GrepQuery("beta", "docs", null, null, null, null, null, null, null, null, null, null)
    ), tempDir);

    assertEquals(2, results.size());
    assertEquals("docs/a.txt", results.get(0).matches().get(0).path());
    assertEquals("docs/a.txt", results.get(1).matches().get(0).path());
  }

  @Test
  void grepMultiQueryPartialFailureKeepsOtherQueries() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/a.txt"), "foo\n");
    Files.writeString(tempDir.resolve("docs/b.txt"), "bar\n");
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("foo", "docs", null, null, null, null, null, null, null, null, null, null),
        new Tools.GrepQuery("[", "docs", null, null, null, null, null, null, null, null, null, null),
        new Tools.GrepQuery("bar", "docs", null, null, null, null, null, null, null, null, null, null)
    ), tempDir);

    assertEquals(3, results.size());
    assertEquals(1, results.get(0).matches().size());
    assertEquals(0, results.get(1).matches().size());
    assertTrue(results.get(1).error() != null);
    assertEquals(1, results.get(2).matches().size());
  }

  @Test
  void grepMultiQueryRejectsEmptyQueries() throws Exception {
    Tools tools = new Tools();
    assertThrows(IllegalArgumentException.class,
        () -> tools.grepMultiQuery(List.of(), tempDir));
  }

  @Test
  void grepMultiQueryRejectsTooManyQueries() throws Exception {
    Tools tools = new Tools();
    List<Tools.GrepQuery> queries = new java.util.ArrayList<>();
    for (int i = 0; i < Tools.MAX_GREP_QUERIES + 1; i++) {
      queries.add(new Tools.GrepQuery("foo", "docs", null, null, null, null, null, null, null, null, null, null));
    }
    assertThrows(IllegalArgumentException.class,
        () -> tools.grepMultiQuery(queries, tempDir));
  }

  @Test
  void searchAndReadReadsContextAroundSingleMatch() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    List<String> lines = new java.util.ArrayList<>();
    for (int i = 1; i <= 100; i++) {
      lines.add("line " + i);
    }
    Files.write(tempDir.resolve("docs/note.txt"), lines);
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.SearchAndReadResult> results = tools.searchAndRead(
        new Tools.SearchAndReadRequest(
            List.of(new Tools.GrepQuery("line 50", "docs", null, null, null, null, null, null, null, null, null, null)),
            5, null), tempDir);

    assertEquals(1, results.size());
    assertEquals("docs/note.txt", results.get(0).path());
    assertEquals(1, results.get(0).matches().size());
    assertEquals(0, results.get(0).matches().get(0).queryIndex());
    assertEquals(50, results.get(0).matches().get(0).line());
    assertEquals(1, results.get(0).sections().size());
    assertEquals(45, results.get(0).sections().get(0).startLine());
    assertEquals(55, results.get(0).sections().get(0).endLine());
    assertEquals(11, results.get(0).sections().get(0).content().size());
  }

  @Test
  void searchAndReadClampsContextToFileStart() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/note.txt"), "alpha\nbeta\ngamma\n");
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.SearchAndReadResult> results = tools.searchAndRead(
        new Tools.SearchAndReadRequest(
            List.of(new Tools.GrepQuery("alpha", "docs", null, null, null, null, null, null, null, null, null, null)),
            10, null), tempDir);

    assertEquals(1, results.size());
    assertEquals(1, results.get(0).sections().get(0).startLine());
    assertEquals(3, results.get(0).sections().get(0).endLine());
    assertEquals(3, results.get(0).sections().get(0).content().size());
  }

  @Test
  void searchAndReadClampsContextToFileEnd() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/note.txt"), "alpha\nbeta\ngamma\n");
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.SearchAndReadResult> results = tools.searchAndRead(
        new Tools.SearchAndReadRequest(
            List.of(new Tools.GrepQuery("gamma", "docs", null, null, null, null, null, null, null, null, null, null)),
            10, null), tempDir);

    assertEquals(1, results.size());
    assertEquals(1, results.get(0).sections().get(0).startLine());
    assertEquals(3, results.get(0).sections().get(0).endLine());
    assertEquals(3, results.get(0).sections().get(0).content().size());
  }

  @Test
  void grepMultiQueryRejectsBlankPattern() throws Exception {
    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("", "docs", null, null, null, null, null, null, null, null, null, null)
    ), tempDir);
    assertEquals(1, results.size());
    assertTrue(results.get(0).error() != null);
  }

  @Test
  void grepMultiQueryLimitsMatchesPerQuery() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      sb.append("foo\n");
    }
    Files.writeString(tempDir.resolve("docs/a.txt"), sb.toString());
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("foo", "docs", null, null, null, null, null, null, 3, null, null, null)
    ), tempDir);

    assertEquals(3, results.get(0).matches().size());
  }

  @Test
  void grepMultiQueryLimitsTotalMatches() throws Exception {
    initGitRepo();
    Files.createDirectories(tempDir.resolve("docs"));
    Files.writeString(tempDir.resolve("docs/a.txt"), "foo\n");
    Files.writeString(tempDir.resolve("docs/b.txt"), "foo\n");
    runGit("add", "docs");
    runGit("commit", "-m", "initial");

    Tools tools = new Tools();
    List<Tools.GrepQueryResult> results = tools.grepMultiQuery(List.of(
        new Tools.GrepQuery("foo", "docs", null, null, null, null, null, null, null, null, null, null),
        new Tools.GrepQuery("foo", "docs", null, null, null, null, null, null, null, null, null, null)
    ), tempDir);

    // 合計制限は MAX_GREP_TOTAL_MATCHES だが、ここでは 2 query それぞれが 2 件ずつ返ることを確認する
    assertEquals(2, results.get(0).matches().size());
    assertEquals(2, results.get(1).matches().size());
  }

  @Test
  void readMultiFileReadsSingleFile() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.write(text, List.of("line1", "line2", "line3"));
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(text.toString(), null, null)
    ));

    assertEquals(1, results.size());
    assertEquals(text.toString(), results.get(0).path());
    assertEquals(List.of("line1", "line2", "line3"), results.get(0).content());
    assertFalse(results.get(0).truncated());
    assertEquals(null, results.get(0).error());
  }

  @Test
  void readMultiFileReadsMultipleFilesAndPreservesOrder() throws Exception {
    Path a = tempDir.resolve("a.txt");
    Path b = tempDir.resolve("b.txt");
    Files.write(a, List.of("alpha"));
    Files.write(b, List.of("beta"));
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(a.toString(), null, null),
        new Tools.ReadFileRequest(b.toString(), null, null)
    ));

    assertEquals(2, results.size());
    assertEquals(a.toString(), results.get(0).path());
    assertEquals(List.of("alpha"), results.get(0).content());
    assertEquals(b.toString(), results.get(1).path());
    assertEquals(List.of("beta"), results.get(1).content());
  }

  @Test
  void readMultiFileSupportsLineRange() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.write(text, List.of("line1", "line2", "line3", "line4"));
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(text.toString(), 2, 3)
    ));

    assertEquals(List.of("line2", "line3"), results.get(0).content());
    assertEquals(2, results.get(0).startLine());
    assertEquals(3, results.get(0).endLine());
  }

  @Test
  void readMultiFileSupportsStartLineOnly() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.write(text, List.of("line1", "line2", "line3"));
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(text.toString(), 2, null)
    ));

    assertEquals(List.of("line2", "line3"), results.get(0).content());
  }

  @Test
  void readMultiFileSupportsNoRange() throws Exception {
    Path text = tempDir.resolve("empty.txt");
    Files.write(text, List.of());
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(text.toString(), null, null)
    ));

    assertEquals(List.of(), results.get(0).content());
  }

  @Test
  void readMultiFileClampsEndLineToEof() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.write(text, List.of("line1", "line2"));
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(text.toString(), 2, 99)
    ));

    assertEquals(List.of("line2"), results.get(0).content());
  }

  @Test
  void readMultiFilePartialFailureKeepsOtherFiles() throws Exception {
    Path a = tempDir.resolve("a.txt");
    Files.write(a, List.of("alpha"));
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(a.toString(), null, null),
        new Tools.ReadFileRequest(tempDir.resolve("missing.txt").toString(), null, null),
        new Tools.ReadFileRequest(a.toString(), null, null)
    ));

    assertEquals(3, results.size());
    assertEquals(List.of("alpha"), results.get(0).content());
    assertTrue(results.get(1).error() != null);
    assertEquals(List.of("alpha"), results.get(2).content());
  }

  @Test
  void readMultiFileRejectsEmptyFiles() throws Exception {
    Tools tools = new Tools();
    assertThrows(IllegalArgumentException.class,
        () -> tools.readMultiFile(List.of()));
  }

  @Test
  void readMultiFileRejectsTooManyFiles() throws Exception {
    Tools tools = new Tools();
    List<Tools.ReadFileRequest> requests = new java.util.ArrayList<>();
    for (int i = 0; i < Tools.MAX_READ_FILES + 1; i++) {
      requests.add(new Tools.ReadFileRequest("a.txt", null, null));
    }
    assertThrows(IllegalArgumentException.class,
        () -> tools.readMultiFile(requests));
  }

  @Test
  void readMultiFileRejectsInvalidLineRange() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.write(text, List.of("line1", "line2"));
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(text.toString(), 3, 2)
    ));

    assertTrue(results.get(0).error() != null);
  }

  @Test
  void readMultiFileRejectsBlankPath() throws Exception {
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest("", null, null)
    ));

    assertTrue(results.get(0).error() != null);
  }

  @Test
  void readMultiFileTruncatesPerFileLimit() throws Exception {
    Path text = tempDir.resolve("note.txt");
    List<String> manyLines = new java.util.ArrayList<>();
    for (int i = 0; i < Tools.MAX_READ_LINES_PER_FILE + 10; i++) {
      manyLines.add("line" + i);
    }
    Files.write(text, manyLines);
    Tools tools = new Tools();

    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(text.toString(), null, null)
    ));

    assertTrue(results.get(0).truncated());
    assertEquals(Tools.MAX_READ_LINES_PER_FILE, results.get(0).content().size());
  }

  @Test
  void readMultiFileMaintainsPathSecurity() throws Exception {
    Path outside = tempDir.getParent().resolve("outside.txt");
    Files.write(outside, List.of("outside"));
    Tools tools = new Tools();

    // 既存 readTextFile と同じく、絶対パスはそのまま解決される（workspace 制約は既存設計に合わせる）
    List<Tools.ReadFileResult> results = tools.readMultiFile(List.of(
        new Tools.ReadFileRequest(outside.toString(), null, null)
    ));

    assertEquals(List.of("outside"), results.get(0).content());
  }

  @Test
  void writeMultiFileWritesSingleFile() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Tools tools = new Tools();

    List<Tools.WriteFileResult> results = tools.writeMultiFile(List.of(
        new Tools.WriteFileRequest(text.toString(), "hello", false, null)
    ));

    assertEquals(1, results.size());
    assertTrue(results.get(0).success());
    assertEquals("hello", Files.readString(text));
  }

  @Test
  void writeMultiFileWritesMultipleFilesAndPreservesOrder() throws Exception {
    Path a = tempDir.resolve("a.txt");
    Path b = tempDir.resolve("b.txt");
    Tools tools = new Tools();

    List<Tools.WriteFileResult> results = tools.writeMultiFile(List.of(
        new Tools.WriteFileRequest(a.toString(), "alpha", false, null),
        new Tools.WriteFileRequest(b.toString(), "beta", false, null)
    ));

    assertEquals(2, results.size());
    assertTrue(results.get(0).success());
    assertTrue(results.get(1).success());
    assertEquals("alpha", Files.readString(a));
    assertEquals("beta", Files.readString(b));
  }

  @Test
  void writeMultiFileCreatesNewFileAndUpdatesExisting() throws Exception {
    Path existing = tempDir.resolve("existing.txt");
    Files.writeString(existing, "old");
    Path created = tempDir.resolve("created.txt");
    Tools tools = new Tools();

    List<Tools.WriteFileResult> results = tools.writeMultiFile(List.of(
        new Tools.WriteFileRequest(existing.toString(), "new", false, null),
        new Tools.WriteFileRequest(created.toString(), "created", false, null)
    ));

    assertEquals("new", Files.readString(existing));
    assertEquals("created", Files.readString(created));
  }

  @Test
  void writeMultiFileRejectsEmptyFiles() throws Exception {
    Tools tools = new Tools();
    assertThrows(IllegalArgumentException.class,
        () -> tools.writeMultiFile(List.of()));
  }

  @Test
  void writeMultiFileRejectsDuplicatePath() throws Exception {
    Path a = tempDir.resolve("a.txt");
    Tools tools = new Tools();

    assertThrows(IllegalArgumentException.class,
        () -> tools.writeMultiFile(List.of(
            new Tools.WriteFileRequest(a.toString(), "one", false, null),
            new Tools.WriteFileRequest(a.toString(), "two", false, null)
        )));
  }

  @Test
  void writeMultiFileRejectsBlankPath() throws Exception {
    Tools tools = new Tools();

    assertThrows(IllegalArgumentException.class,
        () -> tools.writeMultiFile(List.of(
            new Tools.WriteFileRequest("", "content", false, null)
        )));
  }

  @Test
  void writeMultiFileRejectsTooManyFiles() throws Exception {
    Tools tools = new Tools();
    List<Tools.WriteFileRequest> requests = new java.util.ArrayList<>();
    for (int i = 0; i < Tools.MAX_WRITE_FILES + 1; i++) {
      requests.add(new Tools.WriteFileRequest("a" + i + ".txt", "content", false, null));
    }
    assertThrows(IllegalArgumentException.class,
        () -> tools.writeMultiFile(requests));
  }

  @Test
  void writeMultiFileRejectsPerFileSizeLimit() throws Exception {
    Tools tools = new Tools();
    String big = "x".repeat(Tools.MAX_WRITE_CHARS_PER_FILE + 1);

    assertThrows(IllegalArgumentException.class,
        () -> tools.writeMultiFile(List.of(
            new Tools.WriteFileRequest("a.txt", big, false, null)
        )));
  }

  @Test
  void writeMultiFileRejectsAggregateSizeLimit() throws Exception {
    Tools tools = new Tools();
    String big = "x".repeat(Tools.MAX_WRITE_TOTAL_CHARS);

    assertThrows(IllegalArgumentException.class,
        () -> tools.writeMultiFile(List.of(
            new Tools.WriteFileRequest("a.txt", big, false, null)
        )));
  }

  @Test
  void writeMultiFilePerFileIoFailureKeepsOthers() throws Exception {
    Path a = tempDir.resolve("a.txt");
    Tools tools = new Tools();

    // 親ディレクトリは自動作成されるため、両方成功する
    List<Tools.WriteFileResult> results = tools.writeMultiFile(List.of(
        new Tools.WriteFileRequest(a.toString(), "alpha", false, null),
        new Tools.WriteFileRequest(tempDir.resolve("missing").resolve("nested.txt").toString(), "beta", false, null)
    ));

    assertEquals(2, results.size());
    assertTrue(results.get(0).success());
    assertTrue(results.get(1).success());
  }

  @Test
  void writeMultiFileMaintainsPathSecurity() throws Exception {
    Path outside = tempDir.getParent().resolve("outside.txt");
    Tools tools = new Tools();

    // 既存 writeTextFile と同じく、絶対パスはそのまま解決される（workspace 制約は既存設計に合わせる）
    List<Tools.WriteFileResult> results = tools.writeMultiFile(List.of(
        new Tools.WriteFileRequest(outside.toString(), "outside", false, null)
    ));

    assertTrue(results.get(0).success());
    assertEquals("outside", Files.readString(outside));
  }

  @Test
  void resolveShellUsesShellEnvironmentVariableWhenConfigured() {
    Tools tools = new Tools();

    String actual = tools.resolveShell(Map.of("SHELL", "/bin/zsh"), "Windows 11");

    assertEquals("/bin/zsh", actual);
  }

  @Test
  void resolveShellFallsBackToOperatingSystemDefault() {
    Tools tools = new Tools();

    assertEquals("powershell", tools.resolveShell(Map.of(), "Windows 11"));
    assertEquals("bash", tools.resolveShell(Map.of(), "Linux"));
    assertEquals("bash", tools.resolveShell(Map.of(), "Mac OS X"));
  }

  @Test
  void shellCommandLineUsesShellSpecificExecutionOption() {
    Tools tools = new Tools();

    assertEquals(List.of("powershell", "-NoProfile", "-Command", "Write-Output hello"),
        tools.shellCommandLine("powershell", "Write-Output hello"));
    assertEquals(List.of("cmd", "/C", "echo hello"), tools.shellCommandLine("cmd", "echo hello"));
    assertEquals(List.of("/bin/bash", "-lc", "printf hello"), tools.shellCommandLine("/bin/bash", "printf hello"));
  }

  @Test
  void executeShellCommandRunsWithDefaultShell() throws Exception {
    Tools tools = new Tools();
    String osName = System.getProperty("os.name").toLowerCase();
    String command = osName.contains("win") ? "Write-Output hello" : "printf hello";

    Tools.ShellCommandResult result = tools.executeShellCommand(command, 10, tempDir);

    assertEquals(0, result.exitCode());
    assertEquals("hello", result.stdout().trim());
    assertFalse(result.timedOut());
  }

  @Test
  void todayAndNowUseInjectedClock() {
    Clock fixed = Clock.fixed(Instant.parse("2026-08-16T16:35:42Z"), ZoneId.of("Asia/Tokyo"));
    SystemShellService shellService = new SystemShellService();
    Tools tools = new Tools(null, shellService, new BackgroundProcessManager(shellService), fixed);

    assertEquals("2026-08-17", tools.today());
    assertEquals("2026-08-17T01:35:42+09:00", tools.now());
  }

  @Test
  void shellBackgroundProcessToolsSpawnStatusAndKill() throws Exception {
    Tools tools = new Tools();
    String osName = System.getProperty("os.name").toLowerCase();
    String command = osName.contains("win")
        ? "Write-Output ready; Start-Sleep -Seconds 10"
        : "printf 'ready\\n'; sleep 10";

    BackgroundProcessSnapshot spawned = tools.spawnShellCommand(command);
    BackgroundProcessSnapshot ready = awaitToolStdout(tools, spawned.processId(), "ready");
    BackgroundProcessSnapshot killed = tools.killShellProcess(spawned.processId());

    assertEquals(BackgroundProcessStatus.RUNNING, ready.status());
    assertEquals(BackgroundProcessStatus.KILLED, killed.status());
  }

  @Test
  void readTextFileUsesCurrentProjectForRelativePath() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    Files.writeString(project.resolve("note.txt"), "project note");
    ProjectService projectService = new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects"));
    projectService.add(project.toString());
    projectService.cd(project.toString());

    Tools tools = new Tools(projectService);

    assertEquals(List.of("project note"), tools.readTextFile("note.txt"));
  }

  @Test
  void readTextFileFallsBackToCp932() throws Exception {
    Path csv = tempDir.resolve("gantt.csv");
    Files.write(csv, List.of("タスク,開始日", "設計,2026-08-07"), Charset.forName("windows-31j"));

    Tools tools = new Tools();

    assertEquals(List.of("タスク,開始日", "設計,2026-08-07"), tools.readTextFile(csv.toString()));
  }

  @Test
  void readTextFileRangeReadsInclusiveLineRange() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.write(text, List.of("line1", "line2", "line3", "line4"));
    Tools tools = new Tools();

    List<String> lines = tools.readTextFileRange(text.toString(), 2, 3);

    assertEquals(List.of("line2", "line3"), lines);
  }

  @Test
  void readTextFileRangeClampsEndLineToFileEnd() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.write(text, List.of("line1", "line2"));
    Tools tools = new Tools();

    List<String> lines = tools.readTextFileRange(text.toString(), 2, 99);

    assertEquals(List.of("line2"), lines);
  }

  @Test
  void readTextFileRangeFallsBackToCp932() throws Exception {
    Path csv = tempDir.resolve("gantt.csv");
    Files.write(csv, List.of("タスク,開始日", "設計,2026-08-07", "実装,2026-08-08"),
        Charset.forName("windows-31j"));
    Tools tools = new Tools();

    List<String> lines = tools.readTextFileRange(csv.toString(), 2, 2);

    assertEquals(List.of("設計,2026-08-07"), lines);
  }

  @Test
  void writeTextFileUsesSpecifiedCharset() throws Exception {
    Path csv = tempDir.resolve("gantt.csv");
    Tools tools = new Tools();

    tools.writeTextFile(csv.toString(), "タスク,開始日", false, "windows-31j");

    byte[] bytes = Files.readAllBytes(csv);
    assertEquals("タスク,開始日", new String(bytes, Charset.forName("windows-31j")));
    assertFalse(new String(bytes, Charset.forName("UTF-8")).equals("タスク,開始日"));
  }

  @Test
  void writeTextFileDefaultsToUtf8WhenCharsetIsBlank() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Tools tools = new Tools();

    tools.writeTextFile(text.toString(), "メモ", false, "");

    assertEquals("メモ", Files.readString(text, Charset.forName("UTF-8")));
  }

  @Test
  void writeTextFileCreatesParentDirectories() throws Exception {
    Path text = tempDir.resolve("missing").resolve("nested").resolve("note.txt");
    Tools tools = new Tools();

    tools.writeTextFile(text.toString(), "メモ", false, "UTF-8");

    assertEquals("メモ", Files.readString(text, Charset.forName("UTF-8")));
  }

  @Test
  void createDirectoriesCreatesNestedDirectories() throws Exception {
    Path nested = tempDir.resolve("a").resolve("b").resolve("c");
    Tools tools = new Tools();

    String createdPath = tools.createDirectories(nested.toString());

    assertTrue(Files.isDirectory(nested));
    assertEquals(nested.toAbsolutePath().normalize().toString(), createdPath);
  }

  @Test
  void createDirectoriesUsesCurrentProjectForRelativePath() throws Exception {
    Path project = Files.createDirectories(tempDir.resolve("project-a"));
    ProjectService projectService = new ProjectService(tempDir, tempDir.resolve(".rei").resolve("projects"));
    projectService.add(project.toString());
    projectService.cd(project.toString());
    Tools tools = new Tools(projectService);

    String createdPath = tools.createDirectories("docs/specs");

    Path expected = project.resolve("docs").resolve("specs").toAbsolutePath().normalize();
    assertTrue(Files.isDirectory(expected));
    assertEquals(expected.toString(), createdPath);
  }

  @Test
  void applyTextDiffReplacesExpectedText() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.writeString(text, "hello\nworld\n");
    Tools tools = new Tools();

    Tools.TextDiffApplyResult result = tools.applyTextDiff(text.toString(), "world", "rei", "");

    assertTrue(result.success());
    assertTrue(result.changed());
    assertEquals("hello\nrei\n", Files.readString(text));
  }

  @Test
  void applyTextDiffDoesNotChangeFileWhenExpectedTextIsMissing() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.writeString(text, "hello\nworld\n");
    Tools tools = new Tools();

    Tools.TextDiffApplyResult result = tools.applyTextDiff(text.toString(), "missing", "rei", "");

    assertFalse(result.success());
    assertFalse(result.changed());
    assertEquals("hello\nworld\n", Files.readString(text));
  }

  @Test
  void applyTextDiffPrintsExecutionMessageEvenWhenTextIsMissing() throws Exception {
    Path text = tempDir.resolve("note.txt");
    Files.writeString(text, "hello\nworld\n");
    Tools tools = new Tools();

    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    java.io.PrintStream originalOut = System.out;
    System.setOut(new java.io.PrintStream(out));
    try {
      tools.applyTextDiff(text.toString(), "missing", "rei", "");
    } finally {
      System.setOut(originalOut);
    }

    assertTrue(out.toString().contains("applyTextDiff を実行するよ"));
    assertTrue(out.toString().contains(text.toString()));
  }

  private void writePdf(Path pdf, String text) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage();
      document.addPage(page);

      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        contentStream.beginText();
        contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        contentStream.newLineAtOffset(72, 720);
        contentStream.showText(text);
        contentStream.endText();
      }

      document.save(Files.newOutputStream(pdf));
    }
  }

  private void initGitRepo() throws IOException, InterruptedException {
    runGit("init");
    runGit("config", "user.name", "Codex");
    runGit("config", "user.email", "codex@example.com");
  }

  private void runGit(String... args) throws IOException, InterruptedException {
    ProcessBuilder processBuilder = new ProcessBuilder(buildGitCommand(args));
    processBuilder.directory(tempDir.toFile());
    processBuilder.redirectErrorStream(true);
    Process process = processBuilder.start();
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IOException("git command failed: " + String.join(" ", buildGitCommand(args)));
    }
  }

  private BackgroundProcessSnapshot awaitToolStdout(Tools tools, String processId, String expectedLine)
      throws Exception {
    long deadline = System.currentTimeMillis() + 5000;
    BackgroundProcessSnapshot snapshot;
    do {
      snapshot = tools.getShellProcessStatus(processId, 100);
      if (snapshot.stdout().contains(expectedLine)) {
        return snapshot;
      }
      Thread.sleep(50);
    } while (System.currentTimeMillis() < deadline);
    return snapshot;
  }

  private List<String> buildGitCommand(String... args) {
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    return command;
  }

}
