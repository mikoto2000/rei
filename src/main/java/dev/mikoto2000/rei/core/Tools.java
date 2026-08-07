package dev.mikoto2000.rei.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.SystemShellService;

@Component
public class Tools {
  private static final int DEFAULT_SHELL_TIMEOUT_SECONDS = 30;
  private static final int MAX_SHELL_TIMEOUT_SECONDS = 600;
  private static final Charset CP932 = Charset.forName("windows-31j");
  private final ProjectService projectService;
  private final SystemShellService systemShellService;

  public Tools() {
    this(null, new SystemShellService());
  }

  public Tools(ProjectService projectService) {
    this(projectService, new SystemShellService());
  }

  @Autowired
  public Tools(ProjectService projectService, SystemShellService systemShellService) {
    this.projectService = projectService;
    this.systemShellService = systemShellService;
  }

  /**
   * 外部プログラムを実行します。コマンドと引数を指定して実行します。
   *
   * @param command 実行するコマンド
   * @param args コマンドに渡す引数のリスト。null の場合は空のリストとして扱います。
   * @return コマンドの標準出力の内容
   */
  @Tool(name = "executeExternalProgram",
  description = """
  外部プログラムを実行します。コマンドと引数を指定して実行します。
  @param command 実行するコマンド
  @param args コマンドに渡す引数のリスト。null の場合は空のリストとして扱います。
  @return コマンドの標準出力の内容
  """)
    String executeExternalProgram(String command, List<String> args) throws IOException, InterruptedException {
      if (command == null || command.isBlank()) {
        throw new IllegalArgumentException("command は空にできません");
      }
      if (command.isBlank() || command.contains(" ")) {
        throw new IllegalArgumentException(
            "外部コマンドの指定が不正です。command には curl のような実行ファイル名だけを指定し、引数は args に分けて指定してください。");
      }
      List<String> safeArgs = args == null ? List.of() : args;
      IO.println(String.format("%s コマンドを引数 %s で実行するよ", command, safeArgs));

      List<String> commandLine = new ArrayList<>();
      commandLine.add(command);
      commandLine.addAll(safeArgs);

      ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
      processBuilder.directory(currentWorkingDirectory().toFile());
      processBuilder.redirectErrorStream(true);
      Process process = processBuilder.start();

      String output;
      try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
            }

      int exitCode = process.waitFor();
      IO.println(String.format("%s コマンドは終了コード %d で終了したよ", command, exitCode));

      return output;
    }

  @Tool(name = "executeShellCommand",
  description = """
  $SHELL 環境変数で指定されたシェルでコマンド文字列をそのまま実行します。
  $SHELL が未設定の場合、Windows は powershell、Linux と macOS は bash を使います。
  @param command 実行するシェルコマンド文字列
  @param timeoutSeconds タイムアウト秒数。null の場合は 30 秒、最大 600 秒です。
  @return 終了コード、標準出力、標準エラー、タイムアウト有無
  """)
  ShellCommandResult executeShellCommand(String command, Integer timeoutSeconds) throws IOException, InterruptedException {
    return executeShellCommand(command, timeoutSeconds, currentWorkingDirectory());
  }

  ShellCommandResult executeShellCommand(String command, Integer timeoutSeconds, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command は空にできません");
    }

    int effectiveTimeoutSeconds = normalizeShellTimeout(timeoutSeconds);
    String shell = resolveShell(System.getenv(), System.getProperty("os.name"));
    List<String> commandLine = shellCommandLine(shell, command);
    IO.println(String.format("%s でシェルコマンドを実行するよ: %s", shell, command));

    ProcessBuilder processBuilder = new ProcessBuilder(commandLine);
    processBuilder.directory(workingDirectory.toFile());
    Process process = processBuilder.start();

    CompletableFuture<String> stdout = readStreamAsync(process.getInputStream());
    CompletableFuture<String> stderr = readStreamAsync(process.getErrorStream());

    boolean completed = process.waitFor(effectiveTimeoutSeconds, TimeUnit.SECONDS);
    if (!completed) {
      process.destroyForcibly();
    }

    int exitCode = completed ? process.exitValue() : -1;
    String stdoutText = stdout.join();
    String stderrText = stderr.join();
    IO.println(String.format("%s のシェルコマンドは終了コード %d で終了したよ", shell, exitCode));

    return new ShellCommandResult(exitCode, stdoutText, stderrText, !completed);
  }

  String resolveShell(Map<String, String> environment, String osName) {
    return systemShellService.resolveShell(environment, osName);
  }

  List<String> shellCommandLine(String shell, String command) {
    return systemShellService.shellCommandLine(shell, command);
  }

  int normalizeShellTimeout(Integer timeoutSeconds) {
    if (timeoutSeconds == null) {
      return DEFAULT_SHELL_TIMEOUT_SECONDS;
    }
    if (timeoutSeconds < 1) {
      return 1;
    }
    return Math.min(timeoutSeconds, MAX_SHELL_TIMEOUT_SECONDS);
  }

  private CompletableFuture<String> readStreamAsync(java.io.InputStream stream) {
    return CompletableFuture.supplyAsync(() -> {
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
        return reader.lines().collect(Collectors.joining(System.lineSeparator()));
      } catch (IOException e) {
        throw new IllegalStateException("シェルコマンド出力の読み込みに失敗しました", e);
      }
    });
  }

  public record ShellCommandResult(int exitCode, String stdout, String stderr, boolean timedOut) {
  }

  @Tool(name = "rollDice", description = "x 面サイコロをひとつ振る")
  int rollDice(int x) {
    IO.println(String.format("%d 面サイコロをひとつ振るよ", x));
    return (int) (Math.random() * x) + 1;
  }

  @Tool(name = "today", description = "今日の日付を yyyy-MM-dd 形式で取得します")
  String today() {
    IO.println("今日の日付を取得するよ");
    return LocalDate.now().toString();
  }

  @Tool(name = "now", description = "現在時刻を ISO-8601 形式で取得します")
  String now() {
    IO.println("現在時刻を取得するよ");
    return OffsetDateTime.now().toString();
  }

  @Tool(name = "findFile", description = "ファイルを検索します（.gitignore を尊重）")
  List<String> findFile(String fileName) throws IOException, InterruptedException {
    return findFile(fileName, currentWorkingDirectory());
  }

  List<String> findFile(String fileName, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    IO.println(String.format("%s のファイルを探すよ（.gitignore を尊重）", fileName));

    List<String> gitListedFiles = gitLsFiles(List.of(), workingDirectory);
    if (gitListedFiles == null) {
      IO.println("git ls-files コマンドが失敗しました");
      // git が利用できない場合のフォールバック
      return Files.find(workingDirectory, 20, (path, basicFileAttribute) ->
          path.toFile().getAbsolutePath().endsWith(fileName))
        .map(p -> p.toFile().getAbsolutePath())
        .toList();
    }

    return gitListedFiles.stream()
      .filter(s -> s.endsWith(fileName))
      .collect(Collectors.toList());
  }

  @Tool(name = "listFile", description = "ファイル一覧を取得します（.gitignore を尊重）")
  List<String> listFile(String baseDir) throws IOException, InterruptedException {
    return listFile(baseDir, currentWorkingDirectory());
  }

  List<String> listFile(String baseDir, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    IO.println(String.format("%s 以下のファイルを一覧にするよ（.gitignore を尊重）", baseDir));

    List<String> gitListedFiles = gitLsFiles(List.of(baseDir), workingDirectory);
    if (gitListedFiles == null) {
      IO.println("git ls-files コマンドが失敗しました");
      // git が利用できない場合のフォールバック
      return Files.walk(workingDirectory.resolve(baseDir), 20)
        .map(p -> p.toFile().getAbsolutePath())
        .toList();
    }

    return gitListedFiles.stream()
      .filter(s -> s.startsWith(baseDir))
      .collect(Collectors.toList());
  }

  @Tool(name = "grep", description = "指定したディレクトリ配下のテキストを正規表現検索します")
  List<String> grep(String pattern, String baseDir) throws IOException, InterruptedException {
    return grep(pattern, baseDir, currentWorkingDirectory());
  }

  List<String> grep(String pattern, String baseDir, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    if (pattern == null || pattern.isBlank()) {
      throw new IllegalArgumentException("pattern must not be blank");
    }
    if (baseDir == null || baseDir.isBlank()) {
      throw new IllegalArgumentException("baseDir must not be blank");
    }
    Pattern compiled = Pattern.compile(pattern);

    List<String> candidates = listFile(baseDir, workingDirectory);
    List<String> matches = new ArrayList<>();
    for (String relativePath : candidates) {
      java.nio.file.Path filePath = workingDirectory.resolve(relativePath);
      if (!Files.isRegularFile(filePath)) {
        continue;
      }
      List<String> lines;
      try {
        lines = Files.readAllLines(filePath, StandardCharsets.UTF_8);
      } catch (IOException ex) {
        continue;
      }
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        if (compiled.matcher(line).find()) {
          matches.add(String.format("%s:%d:%s", relativePath, i + 1, line));
        }
      }
    }
    return matches;
  }

  List<String> gitLsFiles(List<String> pathSpecs, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    List<String> commandLine = new ArrayList<>(List.of(
        "git",
        "ls-files",
        "-z",
        "--cached",
        "--others",
        "--exclude-standard",
        "--full-name"));
    if (pathSpecs != null && !pathSpecs.isEmpty()) {
      commandLine.add("--");
      commandLine.addAll(pathSpecs);
    }

    ProcessBuilder pb = new ProcessBuilder(commandLine);
    pb.directory(workingDirectory.toFile());
    pb.redirectErrorStream(true);

    Process process = pb.start();
    IO.println("git ls-files コマンドを実行したよ");

    String output;
    try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      output = reader.lines().collect(Collectors.joining(System.lineSeparator()));
          }
    int exitCode = process.waitFor();

    if (exitCode != 0) {
      return null;
    }

    return Arrays.stream(output.split("\0"))
      .filter(s -> !s.isEmpty())
      .toList();
  }

  @Tool(name = "readTextFile", description = "テキストファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  List<String> readTextFile(String pathStr) throws IOException {
    IO.println(String.format("%s のテキストファイルを読むよ", pathStr));
    return readTextFileLines(resolveProjectPath(pathStr), pathStr, "");
  }

  @Tool(name = "readTextFileRange", description = "テキストファイルの指定行範囲を読み込みます。startLine と endLine は 1 始まりで、両端を含みます。")
  List<String> readTextFileRange(String pathStr, int startLine, int endLine) throws IOException {
    if (startLine < 1) {
      throw new IllegalArgumentException("startLine は 1 以上である必要があります");
    }
    if (endLine < startLine) {
      throw new IllegalArgumentException("endLine は startLine 以上である必要があります");
    }

    IO.println(String.format("%s の %d 行目から %d 行目を読むよ", pathStr, startLine, endLine));
    List<String> lines = readTextFileLines(resolveProjectPath(pathStr), pathStr, "");
    int fromIndex = startLine - 1;
    if (fromIndex >= lines.size()) {
      return List.of();
    }
    int toIndex = Math.min(endLine, lines.size());
    return lines.subList(fromIndex, toIndex);
  }

  @Tool(name = "readPdfFile", description = "PDF ファイルから本文テキストを抽出して読み込む。")
  String readPdfFile(String pathStr) throws IOException {
    IO.println(String.format("%s の PDF ファイルを読むよ", pathStr));
    TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(resolveProjectPath(pathStr)));
    return documentReader.get().stream()
      .map(Document::getText)
      .filter(text -> text != null && !text.isBlank())
      .collect(Collectors.joining(System.lineSeparator()));
  }

  /**
   * テキストファイルに書き込みます。ファイルが存在しない場合は作成します。
   *
   * @param pathStr ファイルのパス
   * @param contents 書き込む内容
   * @param append 既存の内容に追記するかどうか。true の場合は追記、false の場合は上書きします。
   */
  @Tool(name = "writeTextFile", description =
  """
  テキストファイルに書き込みます。ファイルが存在しない場合は作成します。
  @param pathStr ファイルのパス
  @param contents 書き込む内容
  @param append 既存の内容に追記するかどうか。true の場合は追記、false の場合は上書きします。
  @param charset 書き込み文字コード。null または空の場合は UTF-8。例: UTF-8, windows-31j
  """)
    void writeTextFile(String pathStr, String contents, boolean append, String charset) throws IOException {
      Charset resolvedCharset = resolveCharset(charset);
      IO.println(String.format("%s のテキストファイルに %s を %s で書き込むよ", pathStr, contents, resolvedCharset.name()));

      OpenOption[] options = null;
      if (append) {
        options = new OpenOption[] {
          StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        };
      } else {
        options = new OpenOption[] {
          StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        };
      }

      Files.writeString(resolveProjectPath(pathStr), contents, resolvedCharset, options);
    }

  @Tool(name = "applyTextDiff", description =
  """
  テキストファイルに小さな差分を適用し、任意のゲートコマンドで検証します。
  oldText がファイル内に一意に存在する場合だけ newText に置換します。
  gateCommand が指定され、終了コードが 0 以外の場合は自動で元内容へロールバックします。
  @param pathStr 対象ファイルのパス
  @param oldText 置換前の期待文字列
  @param newText 置換後の文字列
  @param charset 読み書き文字コード。null または空の場合は UTF-8、UTF-8 で読めない場合は CP932
  @param gateCommand 適用後に実行するシェルコマンド。null または空の場合は実行しません
  @param timeoutSeconds ゲートコマンドのタイムアウト秒数。null の場合は 30 秒、最大 600 秒
  @return 適用結果、ゲート結果、ロールバック有無
  """)
    TextDiffApplyResult applyTextDiff(String pathStr, String oldText, String newText, String charset,
        String gateCommand, Integer timeoutSeconds) throws IOException, InterruptedException {
      IO.println(String.format("applyTextDiff を実行するよ: %s", pathStr));
      if (oldText == null || oldText.isEmpty()) {
        throw new IllegalArgumentException("oldText は空にできません");
      }
      if (newText == null) {
        throw new IllegalArgumentException("newText は null にできません");
      }

      java.nio.file.Path path = resolveProjectPath(pathStr);
      ResolvedTextFile original = readTextFileContent(path, charset);
      String originalContent = original.content();
      int firstIndex = originalContent.indexOf(oldText);
      if (firstIndex < 0) {
        return new TextDiffApplyResult(false, false, false, null, "", "", "oldText が見つかりません");
      }
      int secondIndex = originalContent.indexOf(oldText, firstIndex + oldText.length());
      if (secondIndex >= 0) {
        return new TextDiffApplyResult(false, false, false, null, "", "", "oldText が複数箇所に一致しました");
      }

      String updatedContent = originalContent.substring(0, firstIndex)
          + newText
          + originalContent.substring(firstIndex + oldText.length());
      if (updatedContent.equals(originalContent)) {
        return new TextDiffApplyResult(true, false, false, null, "", "", "変更はありません");
      }

      Files.writeString(path, updatedContent, original.charset(), StandardOpenOption.TRUNCATE_EXISTING);

      if (gateCommand == null || gateCommand.isBlank()) {
        return new TextDiffApplyResult(true, true, false, null, "", "", "差分を適用しました");
      }

      ShellCommandResult gateResult = executeShellCommand(gateCommand, timeoutSeconds, currentWorkingDirectory());
      if (gateResult.exitCode() == 0 && !gateResult.timedOut()) {
        return new TextDiffApplyResult(true, true, false, gateResult.exitCode(), gateResult.stdout(),
            gateResult.stderr(), "差分を適用し、ゲートに成功しました");
      }

      Files.writeString(path, originalContent, original.charset(), StandardOpenOption.TRUNCATE_EXISTING);
      return new TextDiffApplyResult(false, true, true, gateResult.exitCode(), gateResult.stdout(),
          gateResult.stderr(), "ゲートに失敗したためロールバックしました");
    }

  public record TextDiffApplyResult(
      boolean success,
      boolean changed,
      boolean rolledBack,
      Integer gateExitCode,
      String gateStdout,
      String gateStderr,
      String message) {
  }

  @Tool(name = "readBinaryFile", description = "バイナリファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  byte[] readBinaryFile(String pathStr) throws IOException {
    IO.println(String.format("%s のバイナリファイルを読むよ", pathStr));
    return Files.readAllBytes(resolveProjectPath(pathStr));
  }

  @Tool(name = "createDirectories", description = "指定したパスまでのディレクトリをすべて作成します。既に存在するディレクトリは成功扱いです。")
  String createDirectories(String pathStr) throws IOException {
    if (pathStr == null || pathStr.isBlank()) {
      throw new IllegalArgumentException("pathStr は空にできません");
    }
    IO.println(String.format("%s までのディレクトリを作成するよ", pathStr));
    java.nio.file.Path createdPath = Files.createDirectories(resolveProjectPath(pathStr));
    return createdPath.toAbsolutePath().normalize().toString();
  }

  @Tool(name = "writeBinaryFile", description = "バイナリファイルに書き込みます。ファイルが存在しない場合は作成します。")
  void writeBinaryFile(String pathStr, byte[] contents, boolean append) throws IOException {
    IO.println(String.format("%s のバイナリファイルに %s を書き込むよ", pathStr, contents));

    OpenOption[] options = null;
    if (append) {
      options = new OpenOption[] {
        StandardOpenOption.CREATE,
          StandardOpenOption.APPEND
      };
    } else {
      options = new OpenOption[] {
        StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING
      };
    }

    Files.write(resolveProjectPath(pathStr), contents, options);
  }

  @Tool(name = "deleteFile", description = "ファイルを削除します。ファイルが存在しない場合はエラーになります。")
  void deleteFile(String pathStr) throws IOException {
    IO.println(String.format("%s を削除するよ", pathStr));
    Files.delete(resolveProjectPath(pathStr));
  }

  @Tool(name = "copyFile", description = "ファイルをコピーします。上書きする場合は false を指定します。ファイルが存在しない場合はエラーになります。")
  void copyFile(String sourcePath, String destPath, boolean overwrite) throws IOException {
    IO.println(String.format("%s を %s にコピーするよ。上書き：%s", sourcePath, destPath, overwrite));

    java.nio.file.Path resolvedSourcePath = resolveProjectPath(sourcePath);
    java.nio.file.Path resolvedDestPath = resolveProjectPath(destPath);
    boolean exists = Files.exists(resolvedDestPath);
    if (exists && !overwrite) {
      throw new IOException(String.format("%s は既に存在します。上書き：%s", destPath, overwrite));
    }

    Files.copy(resolvedSourcePath, resolvedDestPath, overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.COPY_ATTRIBUTES);
  }

  @Tool(name = "moveFile", description = "ファイルを移動します。上書きする場合は false を指定します。ファイルが存在しない場合はエラーになります。")
  void moveFile(String sourcePath, String destPath, boolean overwrite) throws IOException {
    IO.println(String.format("%s を %s に移動するよ。上書き：%s", sourcePath, destPath, overwrite));

    java.nio.file.Path resolvedSourcePath = resolveProjectPath(sourcePath);
    java.nio.file.Path resolvedDestPath = resolveProjectPath(destPath);
    boolean exists = Files.exists(resolvedDestPath);
    if (exists && !overwrite) {
      throw new IOException(String.format("%s は既に存在します。上書き：%s", destPath, overwrite));
    }

    Files.move(resolvedSourcePath, resolvedDestPath, overwrite ? StandardCopyOption.REPLACE_EXISTING : StandardCopyOption.ATOMIC_MOVE);
  }

  private java.nio.file.Path currentWorkingDirectory() {
    if (projectService == null) {
      return Paths.get(".").toAbsolutePath().normalize();
    }
    return projectService.currentProject();
  }

  private java.nio.file.Path resolveProjectPath(String pathStr) {
    java.nio.file.Path path = Paths.get(pathStr);
    if (path.isAbsolute()) {
      return path.normalize();
    }
    return currentWorkingDirectory().resolve(path).normalize();
  }

  private Charset resolveCharset(String charset) {
    if (charset == null || charset.isBlank()) {
      return StandardCharsets.UTF_8;
    }
    try {
      return Charset.forName(charset);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("Unsupported charset: " + charset, e);
    }
  }

  private ResolvedTextFile readTextFileContent(java.nio.file.Path path, String charset) throws IOException {
    if (charset != null && !charset.isBlank()) {
      Charset resolvedCharset = resolveCharset(charset);
      return new ResolvedTextFile(Files.readString(path, resolvedCharset), resolvedCharset);
    }
    try {
      return new ResolvedTextFile(readStringStrict(path, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    } catch (CharacterCodingException e) {
      return new ResolvedTextFile(Files.readString(path, CP932), CP932);
    }
  }

  private List<String> readTextFileLines(java.nio.file.Path path, String displayPath, String charset) throws IOException {
    if (charset != null && !charset.isBlank()) {
      return Files.readAllLines(path, resolveCharset(charset));
    }
    try {
      return Files.readAllLines(path, StandardCharsets.UTF_8);
    } catch (MalformedInputException e) {
      IO.println(String.format("%s は UTF-8 として読めなかったため CP932 で読み直すよ", displayPath));
      return Files.readAllLines(path, CP932);
    }
  }

  private String readStringStrict(java.nio.file.Path path, Charset charset) throws IOException {
    byte[] bytes = Files.readAllBytes(path);
    return charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString();
  }

  private record ResolvedTextFile(String content, Charset charset) {
  }
}
