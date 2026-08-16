package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
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

import dev.mikoto2000.rei.core.project.ProjectService;

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

  private List<String> buildGitCommand(String... args) {
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(args));
    return command;
  }

}
