package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
