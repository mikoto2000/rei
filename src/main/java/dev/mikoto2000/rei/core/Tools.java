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
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.core.process.BackgroundProcessSnapshot;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.core.service.SystemShellService;
import dev.mikoto2000.rei.core.working.WorkingSet;

@Component
public class Tools {
  private static final int DEFAULT_SHELL_TIMEOUT_SECONDS = 30;
  private static final int MAX_SHELL_TIMEOUT_SECONDS = 600;
  private static final Charset CP932 = Charset.forName("windows-31j");
  private final ProjectService projectService;
  private final SystemShellService systemShellService;
  private final BackgroundProcessManager backgroundProcessManager;
  private final Clock clock;
  private final WorkingSet workingSet;

  public Tools() {
    this(null, new SystemShellService());
  }

  public Tools(ProjectService projectService) {
    this(projectService, new SystemShellService());
  }

  public Tools(ProjectService projectService, SystemShellService systemShellService) {
    this(projectService, systemShellService, new BackgroundProcessManager(systemShellService), Clock.systemDefaultZone());
  }

  public Tools(ProjectService projectService, SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager) {
    this(projectService, systemShellService, backgroundProcessManager, Clock.systemDefaultZone());
  }

  @Autowired
  public Tools(ProjectService projectService, SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager, Clock clock) {
    this(projectService, systemShellService, backgroundProcessManager, clock, new WorkingSet());
  }

  public Tools(ProjectService projectService, SystemShellService systemShellService,
      BackgroundProcessManager backgroundProcessManager, Clock clock, WorkingSet workingSet) {
    this.projectService = projectService;
    this.systemShellService = systemShellService;
    this.backgroundProcessManager = backgroundProcessManager;
    this.clock = clock;
    this.workingSet = workingSet;
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
  $SHELL 環境変数で指定されたシェルで、終了が見込まれるコマンド文字列を同期実行します。
  サーバー、watch、tail、ビルド監視などの長時間実行プロセスには spawnShellCommand を使用してください。
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

  @Tool(name = "spawnShellCommand",
  description = """
  $SHELL 環境変数で指定されたシェルで、長時間実行プロセスをバックグラウンド起動します。
  executeShellCommand と違い、コマンド終了を待たずに processId、OS pid、状態、直近ログを返します。
  サーバー起動、watch、tail、開発サーバーなど、終了しない可能性があるコマンドに使用してください。
  @param command 実行するシェルコマンド文字列
  @return logical processId、OS pid、状態、終了コード、直近の標準出力/標準エラー
  """)
  BackgroundProcessSnapshot spawnShellCommand(String command) {
    return backgroundProcessManager.spawnShell(command, currentWorkingDirectory());
  }

  @Tool(name = "getShellProcessStatus",
  description = """
  spawnShellCommand で起動したバックグラウンドプロセスの状態と直近ログを取得します。
  @param processId spawnShellCommand が返した logical processId
  @param tailLines 返すログ末尾行数。null の場合は既定値です。
  @return 状態、終了コード、直近の標準出力/標準エラー
  """)
  BackgroundProcessSnapshot getShellProcessStatus(String processId, Integer tailLines) {
    return backgroundProcessManager.status(processId, tailLines);
  }

  @Tool(name = "killShellProcess",
  description = """
  spawnShellCommand で起動したバックグラウンドプロセスだけを終了します。
  processId に一致する管理対象プロセスと、その子プロセスツリーを graceful に停止し、残った場合は強制終了します。
  @param processId spawnShellCommand が返した logical processId
  @return 終了後の状態、終了コード、直近の標準出力/標準エラー
  """)
  BackgroundProcessSnapshot killShellProcess(String processId) {
    return backgroundProcessManager.kill(processId);
  }

  @Tool(name = "rollDice", description = "x 面サイコロをひとつ振る")
  int rollDice(int x) {
    IO.println(String.format("%d 面サイコロをひとつ振るよ", x));
    return (int) (Math.random() * x) + 1;
  }

  @Tool(name = "today", description = "今日の日付を yyyy-MM-dd 形式で取得します")
  String today() {
    IO.println("今日の日付を取得するよ");
    return LocalDate.now(clock).toString();
  }

  @Tool(name = "now", description = "現在時刻を ISO-8601 形式で取得します")
  String now() {
    IO.println("現在時刻を取得するよ");
    return OffsetDateTime.now(clock).toString();
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
      java.nio.file.Path resolvedBaseDir = workingDirectory.resolve(baseDir);
      if (!Files.exists(resolvedBaseDir)) {
        IO.println(String.format("%s は存在しません", resolvedBaseDir));
        return List.of();
      }
      return Files.walk(resolvedBaseDir, 20)
        .map(p -> p.toFile().getAbsolutePath())
        .toList();
    }

    return gitListedFiles.stream()
      .filter(s -> s.startsWith(baseDir))
      .collect(Collectors.toList());
  }

  List<String> grep(String pattern, String baseDir, Boolean ignoreCase, Boolean fixedString, Boolean invertMatch,
      Boolean fileNamesOnly, Integer beforeContext, Integer afterContext, Integer maxMatches,
      Boolean includeLineNumber, String includeGlob, String excludeGlob) throws IOException, InterruptedException {
    return grep(pattern, baseDir, ignoreCase, fixedString, invertMatch, fileNamesOnly, beforeContext, afterContext,
        maxMatches, includeLineNumber, includeGlob, excludeGlob, currentWorkingDirectory());
  }

  List<String> grep(String pattern, String baseDir) throws IOException, InterruptedException {
    return grep(pattern, baseDir, currentWorkingDirectory());
  }

  List<String> grep(String pattern, String baseDir, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    return grep(pattern, baseDir, false, false, false, false, 0, 0, 1000, true, null, null, workingDirectory);
  }

  List<String> grep(String pattern, String baseDir, Boolean ignoreCase, Boolean fixedString, Boolean invertMatch,
      Boolean fileNamesOnly, Integer beforeContext, Integer afterContext, Integer maxMatches,
      Boolean includeLineNumber, java.nio.file.Path workingDirectory) throws IOException, InterruptedException {
    return grep(pattern, baseDir, ignoreCase, fixedString, invertMatch, fileNamesOnly, beforeContext, afterContext,
        maxMatches, includeLineNumber, null, null, workingDirectory);
  }

  List<String> grep(String pattern, String baseDir, Boolean ignoreCase, Boolean fixedString, Boolean invertMatch,
      Boolean fileNamesOnly, Integer beforeContext, Integer afterContext, Integer maxMatches,
      Boolean includeLineNumber, String includeGlob, String excludeGlob, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    GrepQuery query = new GrepQuery(pattern, baseDir, ignoreCase, fixedString, invertMatch, fileNamesOnly,
        beforeContext, afterContext, maxMatches, includeLineNumber, includeGlob, excludeGlob);
    List<GrepMatch> matches = grepMatches(query, workingDirectory);
    if (Boolean.TRUE.equals(fileNamesOnly)) {
      return matches.stream().map(GrepMatch::path).distinct().collect(Collectors.toList());
    }
    boolean effectiveIncludeLineNumber = !Boolean.FALSE.equals(includeLineNumber);
    return matches.stream()
        .map(m -> formatGrepLine(m.path(), m.line(), m.content(), m.matched() ? ":" : "-",
            effectiveIncludeLineNumber))
        .collect(Collectors.toList());
  }

  /** 1 リクエストあたりの最大 query 数。 */
  static final int MAX_GREP_QUERIES = 20;

  /** 1 query あたりの最大 match 数。 */
  static final int MAX_GREP_MATCHES_PER_QUERY = 1000;

  /** 全 query 合計の最大 match 数。 */
  static final int MAX_GREP_TOTAL_MATCHES = 5000;

  /**
   * 複数の独立した検索条件を 1 回のツール呼び出しで実行する。
   *
   * <p>検索条件が 1 件だけの場合もこのツールを使用する。</p>
   */
  @Tool(name = "grepMultiQuery", description = """
      1 件以上の独立した検索条件を 1 回のツール呼び出しで実行します。検索条件が 1 件だけの場合もこのツールを使用してください。
      @param queries 検索条件のリスト。
      @return query ごとの結果。queryIndex で入力順と対応する。
      """)
  List<GrepQueryResult> grepMultiQuery(List<GrepQuery> queries) throws IOException, InterruptedException {
    return grepMultiQuery(queries, currentWorkingDirectory());
  }

  List<GrepQueryResult> grepMultiQuery(List<GrepQuery> queries, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    if (queries == null || queries.isEmpty()) {
      throw new IllegalArgumentException("queries must not be empty");
    }
    if (queries.size() > MAX_GREP_QUERIES) {
      throw new IllegalArgumentException("too many queries: " + queries.size() + " (max " + MAX_GREP_QUERIES + ")");
    }
    List<GrepQueryResult> results = new ArrayList<>();
    int totalMatches = 0;
    for (int i = 0; i < queries.size(); i++) {
      GrepQuery query = queries.get(i);
      try {
        List<GrepMatch> matches = grepMatches(query, workingDirectory);
        int remaining = MAX_GREP_TOTAL_MATCHES - totalMatches;
        if (matches.size() > remaining) {
          matches = matches.subList(0, Math.max(0, remaining));
        }
        totalMatches += matches.size();
        results.add(new GrepQueryResult(i, query.pattern(), matches, null));
      } catch (IllegalArgumentException e) {
        results.add(new GrepQueryResult(i, query.pattern(), List.of(), e.getMessage()));
      }
    }
    return results;
  }

  /** 1 query の検索を実行し、構造化された match のリストを返す。 */
  private List<GrepMatch> grepMatches(GrepQuery query, java.nio.file.Path workingDirectory)
      throws IOException, InterruptedException {
    if (query.pattern() == null || query.pattern().isBlank()) {
      throw new IllegalArgumentException("pattern must not be blank");
    }
    if (query.baseDir() == null || query.baseDir().isBlank()) {
      throw new IllegalArgumentException("baseDir must not be blank");
    }
    boolean effectiveIgnoreCase = Boolean.TRUE.equals(query.ignoreCase());
    boolean effectiveFixedString = Boolean.TRUE.equals(query.fixedString());
    boolean effectiveInvertMatch = Boolean.TRUE.equals(query.invertMatch());
    boolean effectiveFileNamesOnly = Boolean.TRUE.equals(query.fileNamesOnly());
    int effectiveBeforeContext = Math.max(0, query.beforeContext() == null ? 0 : query.beforeContext());
    int effectiveAfterContext = Math.max(0, query.afterContext() == null ? 0 : query.afterContext());
    int effectiveMaxMatches = query.maxMatches() == null || query.maxMatches() <= 0
        ? MAX_GREP_MATCHES_PER_QUERY : query.maxMatches();
    Pattern compiled = compileGrepPattern(query.pattern(), effectiveIgnoreCase, effectiveFixedString);
    PathMatcher includeMatcher = globMatcher(query.includeGlob(), workingDirectory);
    PathMatcher excludeMatcher = globMatcher(query.excludeGlob(), workingDirectory);

    List<String> candidates = listFile(query.baseDir(), workingDirectory);
    List<GrepMatch> matches = new ArrayList<>();
    for (String relativePath : candidates) {
      if (!matchesGlob(relativePath, includeMatcher, excludeMatcher, workingDirectory)) {
        continue;
      }
      java.nio.file.Path filePath = workingDirectory.resolve(relativePath);
      if (!Files.isRegularFile(filePath)) {
        continue;
      }
      List<String> lines;
      try {
        lines = readTextFileLines(filePath, relativePath, "");
      } catch (IOException ex) {
        continue;
      }
      Set<Integer> contextLineIndexes = new LinkedHashSet<>();
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        boolean matched = compiled.matcher(line).find();
        if (effectiveInvertMatch) {
          matched = !matched;
        }
        if (matched) {
          if (effectiveFileNamesOnly) {
            matches.add(new GrepMatch(relativePath, i + 1, line, true));
            break;
          }
          int from = Math.max(0, i - effectiveBeforeContext);
          int to = Math.min(lines.size() - 1, i + effectiveAfterContext);
          for (int contextIndex = from; contextIndex <= to; contextIndex++) {
            contextLineIndexes.add(contextIndex);
          }
        }
      }

      if (!effectiveFileNamesOnly) {
        for (Integer lineIndex : contextLineIndexes) {
          matches.add(new GrepMatch(relativePath, lineIndex + 1, lines.get(lineIndex),
              isMatchedLine(compiled, lines.get(lineIndex), effectiveInvertMatch)));
          if (matches.size() >= effectiveMaxMatches) {
            return matches;
          }
        }
      }
      if (matches.size() >= effectiveMaxMatches) {
        return matches;
      }
    }
    return matches;
  }

  /** 1 つの grep 検索条件。grep ツールのパラメータと 1:1 対応する。 */
  public record GrepQuery(
      String pattern,
      String baseDir,
      Boolean ignoreCase,
      Boolean fixedString,
      Boolean invertMatch,
      Boolean fileNamesOnly,
      Integer beforeContext,
      Integer afterContext,
      Integer maxMatches,
      Boolean includeLineNumber,
      String includeGlob,
      String excludeGlob) {
  }

  /** 1 行の grep 検索結果。 */
  public record GrepMatch(String path, int line, String content, boolean matched) {
  }

  /** 1 query の検索結果。queryIndex は入力順と対応する。 */
  public record GrepQueryResult(int queryIndex, String pattern, List<GrepMatch> matches, String error) {
  }

  /** 1 リクエストあたりの最大ファイル数。 */
  static final int MAX_READ_FILES = 20;

  /** 1 ファイルあたりの最大行数。 */
  static final int MAX_READ_LINES_PER_FILE = 1000;

  /** リクエスト全体の最大行数。 */
  static final int MAX_READ_TOTAL_LINES = 5000;

  /**
   * 複数のファイルまたは行範囲を 1 回のツール呼び出しで読み込む。
   *
   * <p>ファイルが 1 件だけの場合もこのツールを使用する。</p>
   */
  @Tool(name = "readMultiFile", description = """
      1 件以上のファイルまたは行範囲を 1 回のツール呼び出しで読み込みます。ファイルが 1 件だけの場合もこのツールを使用してください。
      @param files 読み込むファイルのリスト。各要素は path と任意の startLine / endLine を持つ。
      @return ファイルごとの読み込み結果。path で識別できる。
      """)
  List<ReadFileResult> readMultiFile(List<ReadFileRequest> files) throws IOException {
    return readMultiFile(files, currentWorkingDirectory());
  }

  List<ReadFileResult> readMultiFile(List<ReadFileRequest> files, java.nio.file.Path workingDirectory)
      throws IOException {
    if (files == null || files.isEmpty()) {
      throw new IllegalArgumentException("files must not be empty");
    }
    if (files.size() > MAX_READ_FILES) {
      throw new IllegalArgumentException("too many files: " + files.size() + " (max " + MAX_READ_FILES + ")");
    }
    List<ReadFileResult> results = new ArrayList<>();
    int totalLines = 0;
    for (ReadFileRequest request : files) {
      try {
        ReadFileResult result = readSingleFile(request, workingDirectory);
        int remaining = MAX_READ_TOTAL_LINES - totalLines;
        if (result.content().size() > remaining) {
          result = new ReadFileResult(result.path(), result.startLine(), result.endLine(),
              result.content().subList(0, Math.max(0, remaining)), true, null);
        }
        totalLines += result.content().size();
        results.add(result);
      } catch (IllegalArgumentException | IOException e) {
        results.add(new ReadFileResult(request.path(), null, null, List.of(), false, e.getMessage()));
      }
    }
    return results;
  }

  /** 1 ファイルの読み込みを実行する。 */
  private ReadFileResult readSingleFile(ReadFileRequest request, java.nio.file.Path workingDirectory)
      throws IOException {
    if (request.path() == null || request.path().isBlank()) {
      throw new IllegalArgumentException("path must not be blank");
    }
    Integer startLine = request.startLine();
    Integer endLine = request.endLine();
    if (startLine != null && startLine < 1) {
      throw new IllegalArgumentException("startLine は 1 以上である必要があります");
    }
    if (endLine != null && startLine != null && endLine < startLine) {
      throw new IllegalArgumentException("endLine は startLine 以上である必要があります");
    }
    java.nio.file.Path path = resolveProjectPath(request.path(), workingDirectory);
    List<String> lines = readTextFileLines(path, request.path(), "");
    workingSet.recordRead(path);
    int fromIndex = startLine == null ? 0 : startLine - 1;
    if (fromIndex >= lines.size()) {
      return new ReadFileResult(request.path(), startLine, endLine, List.of(), false, null);
    }
    int toIndex = endLine == null ? lines.size() : Math.min(endLine, lines.size());
    List<String> content = lines.subList(fromIndex, toIndex);
    boolean truncated = content.size() > MAX_READ_LINES_PER_FILE;
    if (truncated) {
      content = content.subList(0, MAX_READ_LINES_PER_FILE);
    }
    return new ReadFileResult(request.path(), startLine, endLine, content, truncated, null);
  }

  /** 1 ファイルの読み込み要求。startLine / endLine は任意。 */
  public record ReadFileRequest(String path, Integer startLine, Integer endLine) {
  }

  /** 1 ファイルの読み込み結果。 */
  public record ReadFileResult(String path, Integer startLine, Integer endLine, List<String> content,
      boolean truncated, String error) {
  }

  /** 1 リクエストあたりの最大ファイル数。 */
  static final int MAX_WRITE_FILES = 20;

  /** 1 ファイルあたりの最大 content サイズ（文字数）。 */
  static final int MAX_WRITE_CHARS_PER_FILE = 100_000;

  /** リクエスト全体の最大 content サイズ（文字数）。 */
  static final int MAX_WRITE_TOTAL_CHARS = 500_000;

  /**
   * 複数の既知ファイルを 1 回のツール呼び出しで書き込む。
   *
   * <p>ファイルが 1 件だけの場合もこのツールを使用する。</p>
   */
  @Tool(name = "writeMultiFile", description = """
      1 件以上の既知ファイルを 1 回のツール呼び出しで書き込みます。ファイルが 1 件だけの場合もこのツールを使用してください。
      @param files 書き込むファイルのリスト。各要素は path と content を持つ。
      @return ファイルごとの書き込み結果。path で識別できる。
      """)
  List<WriteFileResult> writeMultiFile(List<WriteFileRequest> files) throws IOException {
    return writeMultiFile(files, currentWorkingDirectory());
  }

  List<WriteFileResult> writeMultiFile(List<WriteFileRequest> files, java.nio.file.Path workingDirectory)
      throws IOException {
    if (files == null || files.isEmpty()) {
      throw new IllegalArgumentException("files must not be empty");
    }
    if (files.size() > MAX_WRITE_FILES) {
      throw new IllegalArgumentException("too many files: " + files.size() + " (max " + MAX_WRITE_FILES + ")");
    }
    // 書き込み開始前に全件 validation を実施する（入力段階で検出可能なエラーを先に検出）
    List<WriteFileRequest> validated = new ArrayList<>();
    Set<String> seenPaths = new LinkedHashSet<>();
    int totalChars = 0;
    for (WriteFileRequest request : files) {
      String validationError = validateWriteRequest(request, workingDirectory);
      if (validationError != null) {
        throw new IllegalArgumentException(validationError);
      }
      String normalized = resolveProjectPath(request.path(), workingDirectory).toString();
      if (!seenPaths.add(normalized)) {
        throw new IllegalArgumentException("duplicate path: " + request.path());
      }
      totalChars += request.content() == null ? 0 : request.content().length();
      if (totalChars > MAX_WRITE_TOTAL_CHARS) {
        throw new IllegalArgumentException("total content size exceeds limit");
      }
      validated.add(request);
    }
    List<WriteFileResult> results = new ArrayList<>();
    for (WriteFileRequest request : validated) {
      try {
        writeSingleFile(request, workingDirectory);
        results.add(new WriteFileResult(request.path(), true, null));
      } catch (IOException e) {
        results.add(new WriteFileResult(request.path(), false, e.getMessage()));
      }
    }
    return results;
  }

  /** 書き込み要求を検証する。問題があればエラーメッセージを返す。 */
  private String validateWriteRequest(WriteFileRequest request, java.nio.file.Path workingDirectory) {
    if (request.path() == null || request.path().isBlank()) {
      return "path must not be blank";
    }
    if (request.content() == null) {
      return "content must not be null";
    }
    if (request.content().length() > MAX_WRITE_CHARS_PER_FILE) {
      return "content size exceeds per-file limit";
    }
    return null;
  }

  /** 1 ファイルの書き込みを実行する。 */
  private void writeSingleFile(WriteFileRequest request, java.nio.file.Path workingDirectory) throws IOException {
    Charset resolvedCharset = resolveCharset(request.charset());
    OpenOption[] options = Boolean.TRUE.equals(request.append())
        ? new OpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.APPEND }
        : new OpenOption[] { StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING };
    java.nio.file.Path path = resolveProjectPath(request.path(), workingDirectory);
    java.nio.file.Path parent = path.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(path, request.content(), resolvedCharset, options);
    if (Boolean.TRUE.equals(request.append())) {
      workingSet.recordEdit(path);
    } else {
      workingSet.recordWrite(path);
    }
  }

  /** 1 ファイルの書き込み要求。 */
  public record WriteFileRequest(String path, String content, Boolean append, String charset) {
  }

  /** 1 ファイルの書き込み結果。 */
  public record WriteFileResult(String path, boolean success, String error) {
  }

  private PathMatcher globMatcher(String glob, java.nio.file.Path workingDirectory) {
    if (glob == null || glob.isBlank()) {
      return null;
    }
    return workingDirectory.getFileSystem().getPathMatcher("glob:" + glob);
  }

  private boolean matchesGlob(String relativePath, PathMatcher includeMatcher, PathMatcher excludeMatcher,
      java.nio.file.Path workingDirectory) {
    java.nio.file.Path normalizedPath = Paths.get(relativePath.replace('\\', '/'));
    if (includeMatcher != null && !includeMatcher.matches(normalizedPath)) {
      return false;
    }
    return excludeMatcher == null || !excludeMatcher.matches(normalizedPath);
  }

  private Pattern compileGrepPattern(String pattern, boolean ignoreCase, boolean fixedString) {
    int flags = ignoreCase ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
    String effectivePattern = fixedString ? Pattern.quote(pattern) : pattern;
    try {
      return Pattern.compile(effectivePattern, flags);
    } catch (PatternSyntaxException e) {
      throw new IllegalArgumentException("Invalid grep pattern: " + pattern, e);
    }
  }

  private boolean isMatchedLine(Pattern pattern, String line, boolean invertMatch) {
    boolean matched = pattern.matcher(line).find();
    return invertMatch ? !matched : matched;
  }

  private String formatGrepLine(String path, int lineNumber, String line, String separator, boolean includeLineNumber) {
    if (includeLineNumber) {
      return path + separator + lineNumber + separator + line;
    }
    return path + separator + line;
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

  List<String> readTextFile(String pathStr) throws IOException {
    IO.println(String.format("%s のテキストファイルを読むよ", pathStr));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    List<String> lines = readTextFileLines(path, pathStr, "");
    workingSet.recordRead(path);
    return lines;
  }

  List<String> readTextFileRange(String pathStr, int startLine, int endLine) throws IOException {
    if (startLine < 1) {
      throw new IllegalArgumentException("startLine は 1 以上である必要があります");
    }
    if (endLine < startLine) {
      throw new IllegalArgumentException("endLine は startLine 以上である必要があります");
    }

    IO.println(String.format("%s の %d 行目から %d 行目を読むよ", pathStr, startLine, endLine));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    List<String> lines = readTextFileLines(path, pathStr, "");
    workingSet.recordRead(path);
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
    java.nio.file.Path path = resolveProjectPath(pathStr);
    TikaDocumentReader documentReader = new TikaDocumentReader(new FileSystemResource(path));
    String extracted = documentReader.get().stream()
      .map(Document::getText)
      .filter(text -> text != null && !text.isBlank())
      .collect(Collectors.joining(System.lineSeparator()));
    workingSet.recordRead(path);
    return extracted;
  }

  /**
   * テキストファイルに書き込みます。ファイルが存在しない場合は作成します。
   *
   * @param pathStr ファイルのパス
   * @param contents 書き込む内容
   * @param append 既存の内容に追記するかどうか。true の場合は追記、false の場合は上書きします。
   */
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

      java.nio.file.Path path = resolveProjectPath(pathStr);
      java.nio.file.Path parent = path.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(path, contents, resolvedCharset, options);
      if (append) {
        workingSet.recordEdit(path);
      } else {
        workingSet.recordWrite(path);
      }
    }

  @Tool(name = "applyTextDiff", description =
  """
  テキストファイルに小さな差分を適用します。
  oldText がファイル内に一意に存在する場合だけ newText に置換します。
  @param pathStr 対象ファイルのパス
  @param oldText 置換前の期待文字列
  @param newText 置換後の文字列
  @param charset 読み書き文字コード。null または空の場合は UTF-8、UTF-8 で読めない場合は CP932
  @return 適用結果
  """)
    TextDiffApplyResult applyTextDiff(String pathStr, String oldText, String newText, String charset)
        throws IOException {
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
        return new TextDiffApplyResult(false, false, "oldText が見つかりません");
      }
      int secondIndex = originalContent.indexOf(oldText, firstIndex + oldText.length());
      if (secondIndex >= 0) {
        return new TextDiffApplyResult(false, false, "oldText が複数箇所に一致しました");
      }

      String updatedContent = originalContent.substring(0, firstIndex)
          + newText
          + originalContent.substring(firstIndex + oldText.length());
      if (updatedContent.equals(originalContent)) {
        return new TextDiffApplyResult(true, false, "変更はありません");
      }

      Files.writeString(path, updatedContent, original.charset(), StandardOpenOption.TRUNCATE_EXISTING);
      workingSet.recordEdit(path);
      return new TextDiffApplyResult(true, true, "差分を適用しました");
    }

  public record TextDiffApplyResult(
      boolean success,
      boolean changed,
      String message) {
  }

  @Tool(name = "readBinaryFile", description = "バイナリファイルをすべて読み込む。ファイルが存在しない場合は findFile を利用してファイルを探す。")
  byte[] readBinaryFile(String pathStr) throws IOException {
    IO.println(String.format("%s のバイナリファイルを読むよ", pathStr));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    byte[] bytes = Files.readAllBytes(path);
    workingSet.recordRead(path);
    return bytes;
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

    java.nio.file.Path path = resolveProjectPath(pathStr);
    Files.write(path, contents, options);
    if (append) {
      workingSet.recordEdit(path);
    } else {
      workingSet.recordWrite(path);
    }
  }

  @Tool(name = "deleteFile", description = "ファイルを削除します。ファイルが存在しない場合はエラーになります。")
  void deleteFile(String pathStr) throws IOException {
    IO.println(String.format("%s を削除するよ", pathStr));
    java.nio.file.Path path = resolveProjectPath(pathStr);
    Files.delete(path);
    workingSet.remove(path);
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
    workingSet.recordCreate(resolvedDestPath);
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
    workingSet.recordCreate(resolvedDestPath);
  }

  WorkingSet workingSet() {
    return workingSet;
  }

  private java.nio.file.Path currentWorkingDirectory() {
    if (projectService == null) {
      return Paths.get(".").toAbsolutePath().normalize();
    }
    return projectService.currentProject();
  }

  private java.nio.file.Path resolveProjectPath(String pathStr) {
    return resolveProjectPath(pathStr, currentWorkingDirectory());
  }

  private java.nio.file.Path resolveProjectPath(String pathStr, java.nio.file.Path workingDirectory) {
    java.nio.file.Path path = Paths.get(pathStr);
    if (path.isAbsolute()) {
      return path.normalize();
    }
    return workingDirectory.resolve(path).normalize();
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
