package dev.mikoto2000.rei.cli;

import com.fasterxml.jackson.databind.JsonNode;
import dev.mikoto2000.rei.core.completion.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.jline.reader.*;
import org.jline.reader.impl.history.DefaultHistory;
import org.jline.terminal.*;

/**
 * 終端を外部の接続先へ依頼する窓口。
 *
 * <p>Client-local selection, JLine input and ordinary terminal output.
 */
public final class TerminalClient implements AutoCloseable {
  /** 再開確認済み。 */
  private final dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory resumeSeen =
      new dev.mikoto2000.rei.checkpoint.ResumeOverview.DisplayHistory();

  /** 再開起動時読み取り／再開自動有効状態。 */
  private boolean resumeStartupRead, resumeAutoEnabled = true;

  /** 再開タイムゾーン。 */
  private java.time.ZoneId resumeZone = java.time.ZoneId.systemDefault();

  /** バックエンド。 */
  private final BackendClient backend;

  /** 終端。 */
  private final Terminal terminal;

  /** 読み取り元。 */
  private final LineReader reader;

  /** 履歴。 */
  private final ClientHistory history;

  /** 逐次データ一覧。 */
  private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

  /** 通知の監視を担当する処理。 */
  private Future<?> watching;

  /** 接続済みかどうか。 */
  private volatile boolean attached;

  /** 購読生成。 */
  private final AtomicLong subscriptionGeneration = new AtomicLong();

  /** 動作モード。 */
  private String mode = "EXCLUSIVE";

  /** プロジェクト／会話。 */
  private volatile String project, session;

  /** プロジェクト名前／実行。 */
  private String projectName = "none", run;

  /** 現在モデル。 */
  private String currentModel = "unknown";

  /** プロジェクトディレクトリ。 */
  private Path projectDirectory;

  /** プロジェクト識別子一覧／会話識別子一覧。 */
  private volatile List<String> projectIds = List.of(), sessionIds = List.of();

  /** モデル識別子一覧。 */
  private volatile List<String> modelIds = List.of();

  /** プロジェクトパス一覧。 */
  private volatile List<String> projectPaths = List.of();

  /** 全対象会話識別子一覧。 */
  private volatile List<String> allSessionIds = List.of();

  /** 利用可能な項目。 */
  private volatile CachedCatalog catalog =
      new CachedCatalog(new dev.mikoto2000.rei.application.cli.CliCatalog(List.of()));

  /** 利用可能な項目読み込み済みの内容。 */
  private boolean catalogLoaded;

  /** 記録履歴。 */
  private boolean recordHistory;

  /** 承認別名一覧。 */
  private final ApprovalAliases approvalAliases = new ApprovalAliases();

  /** 承認操作の選択肢。 */
  private final ApprovalActionMenu approvalMenu;

  /** コマンド一覧。 */
  private static final List<String> COMMANDS =
      java.util.stream.Stream.concat(
              java.util.stream.Stream.of(
                  "/help",
                  "/exit",
                  "/project",
                  "/session",
                  "/chat",
                  "/run-mode",
                  "/cancel",
                  "/runs",
                  "/watch",
                  "/receipt",
                  "/input-history",
                  "/paste",
                  "/sh"),
              RemoteCommands.NAMES.stream())
          .sorted()
          .toList();

  /** 軽量CLIでの対話を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public TerminalClient(BackendClient backend, Path historyDirectory, boolean noHistory)
      throws IOException {
    this(backend, historyDirectory, noHistory, TerminalBuilder.builder().system(true).build());
  }

  /** 指定された値や利用先を使って、TerminalClientを初期化する。 */
  TerminalClient(BackendClient backend, Path historyDirectory, boolean noHistory, Terminal terminal)
      throws IOException {
    this.backend = backend;
    history = new ClientHistory(historyDirectory);
    recordHistory = !noHistory;
    history.enabled(recordHistory);
    this.terminal = terminal;
    var memory =
        new DefaultHistory() {
          /** 新たな対象を登録し、後から利用できるようにする。 */
          @Override
          public void add(java.time.Instant time, String line) {
            if (recordHistory && history.accepts(line)) super.add(time, line);
          }
        };
    var menuReader = new ApprovalMenuLineReader(terminal);
    menuReader.setParser(commandParser());
    menuReader.setHistory(memory);
    menuReader.setCompleter(this::complete);
    reader = menuReader;
    reader.setVariable(LineReader.HISTORY_SIZE, 1000);
    reader.setVariable(LineReader.HISTORY_FILE_SIZE, 1000);
    reader.setOpt(LineReader.Option.BRACKETED_PASTE);
    approvalMenu =
        new ApprovalActionMenu(reader, () -> new ApprovalActionMenu.Owner(project, session));
    approvalMenu.follow(this::pendingApprovals);
    if (java.io.File.separatorChar == '\\') reader.setOpt(LineReader.Option.CASE_INSENSITIVE);
    if (recordHistory)
      for (String line : history.entries()) memory.add(java.time.Instant.now(), line);
    terminal.handle(
        Terminal.Signal.INT,
        signal -> {
          backend.detach();
          attached = false;
        });
  }

  /** 軽量CLIでの対話で用いる待機中承認一覧を返す。 */
  List<String> pendingApprovals(ApprovalActionMenu.Owner owner)
      throws IOException, InterruptedException {
    if (owner.project() == null || owner.session() == null) return List.of();
    var rows =
        backend.get("/api/v1/projects/" + BackendClient.segment(owner.project()) + "/approvals");
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (!rows.isArray() || rows.size() > 256) throw new IOException("Invalid approval list");
    var ids = new ArrayList<String>();
    var activeRuns = new HashMap<String, Boolean>();
    for (var row : rows) {
      if (!owner.project().equals(row.path("projectId").asText()))
        throw new IOException("Invalid approval ownership");
      if (owner.session().equals(row.path("sessionId").asText())
          && ApprovalDisplayState.pending(
              row.path("status").asText(),
              java.time.Instant.parse(row.path("expiresAt").asText()),
              row.path("continuationState").asText("UNAVAILABLE"),
              java.time.Instant.now())) {
        // Legacy requests can have no continuation record; still remove terminal/cancelled Runs.
        if (row.path("continuationState").asText("UNAVAILABLE").equals("UNAVAILABLE")) {
          String runId = row.path("runId").asText();
          if (runId.isBlank()) throw new IOException("Invalid approval Run");
          if (!activeRuns.containsKey(runId)) {
            var status =
                backend.get("/api/v1/runs/" + BackendClient.segment(runId)).path("status").asText();
            activeRuns.put(
                runId, Set.of("QUEUED", "RUNNING", "WAITING", "WAITING_APPROVAL").contains(status));
          }
          if (!activeRuns.get(runId)) continue;
        }
        ids.add(row.path("id").asText());
      }
    }
    return ids;
  }

  /** 条件に合う対象を利用するため、初期プロジェクトを選択する。 */
  public void selectInitialProject(Path path) throws IOException, InterruptedException {
    Path selected = (path == null ? Path.of("") : path).toAbsolutePath().normalize();
    var value = backend.post("/api/v1/projects/register", Map.of("path", selected.toString()));
    selectProject(value.path("id").asText());
  }

  /** パス一覧。 */
  private final CompletionEngine paths =
      new CompletionEngine().register(new FilePathCompletionProvider());

  /** 処理の完了を記録し、後続の処理へ結果を引き渡す。 */
  private void complete(LineReader input, ParsedLine line, List<Candidate> candidates) {
    List<String> values = completionValues(line.words(), line.wordIndex(), line.word());
    for (String value : values)
      if (value.startsWith(line.word())) {
        var candidate = catalog.presentation(line.words(), line.wordIndex(), value);
        candidates.add(
            new Candidate(
                candidate.value(),
                candidate.display(),
                candidate.kind(),
                candidate.description(),
                null,
                null,
                candidate.appendSpace()));
      }
    Set<String> types = catalog.pathTypes(line.words(), line.wordIndex(), line.word());
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (line.words().size() > 1
        && line.words().getFirst().equals("/project")
        && Set.of("register", "add", "cd").contains(line.words().get(1)))
      types = Set.of("directory");
    if (!types.isEmpty()) {
      var context =
          new CompletionContext(
              line.line(),
              line.cursor(),
              line.words(),
              line.wordIndex(),
              line.wordIndex() - 1,
              line.word(),
              List.of(),
              types,
              List.of(),
              projectDirectory == null ? Path.of("").toAbsolutePath() : projectDirectory);
      for (var value : paths.complete(context))
        candidates.add(
            new Candidate(
                value.value(),
                value.display(),
                value.kind(),
                value.description(),
                null,
                null,
                value.appendSpace()));
    }
  }

  /** 軽量CLIでの対話で用いる入力補完値一覧を返す。 */
  List<String> completionValues(List<String> words, int wordIndex, String prefix) {
    var values = new LinkedHashSet<>(catalog.complete(words, wordIndex, prefix));
    if (wordIndex == 0) values.addAll(COMMANDS);
    if (wordIndex == 1 && !words.isEmpty() && words.getFirst().equals("/model"))
      values.addAll(modelIds);
    String type = catalog.candidateType(words, wordIndex);
    if (type.endsWith("$RegisteredProjectCandidates")
        || type.endsWith("$ProjectDirectoryCandidates")) values.addAll(projectPaths);
    if (type.endsWith("SessionCompletionCandidates$CurrentProject")) values.addAll(sessionIds);
    else if (type.endsWith("SessionCompletionCandidates")) values.addAll(allSessionIds);
    if (!words.isEmpty() && words.getFirst().equals("/agent")) {
      if (wordIndex == 1) values.addAll(List.of("codex", "claude"));
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (wordIndex == 2 && words.size() > 1 && Set.of("codex", "claude").contains(words.get(1))) {
        values.addAll(List.of("review", "implement", "implementation", "merge"));
        if (words.get(1).equals("codex")) values.add("material-review");
      }
    }
    if (wordIndex == 2 && words.size() > 1) {
      if (words.getFirst().equals("/project") && words.get(1).equals("select"))
        values.addAll(projectIds);
      if (words.getFirst().equals("/session")
          && Set.of("select", "switch", "resume").contains(words.get(1))) values.addAll(sessionIds);
    }
    return values.stream().filter(value -> value.startsWith(prefix)).toList();
  }

  /** 軽量CLIでの対話に関する再確認利用可能な項目を処理する。 */
  void refreshCatalog() throws IOException, InterruptedException {
    if (catalogLoaded) return;
    try {
      catalog =
          new CachedCatalog(
              BackendClient.JSON.treeToValue(
                  backend.get("/api/v1/cli/catalog"),
                  dev.mikoto2000.rei.application.cli.CliCatalog.class));
    } catch (BackendClient.ApiException missing) {
      if (missing.status() != 404) throw missing;
    }
    catalogLoaded = true;
  }

  /** 軽量CLIでの対話の操作を実行する。 */
  public void run() throws IOException {
    try {
      refresh();
    } catch (Exception error) {
      out(
          "Completion metadata unavailable; /project list can retry. 原因: "
              + dev.mikoto2000.rei.event.FailureMessages.describe(error));
    }
    out("Connected. /help lists commands. Ctrl+C detaches output; /cancel RUN_ID cancels a Run.");
    while (true) {
      String line;
      try {
        approvalMenu.enabled(true);
        try {
          line = reader.readLine(buildPrompt(java.time.LocalTime.now()));
        } finally {
          approvalMenu.enabled(false);
        }
      } catch (UserInterruptException interrupt) {
        backend.detach();
        attached = false;
        out("入力または表示を Ctrl-C で中断しました。Backend の実行は継続します。");
        continue;
      } catch (EndOfFileException eof) {
        return;
      }
      if (line.isBlank()) continue;
      if (line.equals("/exit") || line.equals("/quit")) return;
      try {
        if (line.equals("/paste")) {
          var paste = new StringBuilder();
          for (; ; ) {
            String part = reader.readLine("paste> ");
            if (part.equals(".")) break;
            // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
            if (paste.length() + part.length() > 16384)
              throw new IllegalArgumentException("Input capacity exceeded");
            paste.append(part).append('\n');
          }
          send(paste.toString());
          continue;
        }
        var message = new StringBuilder(line);
        while (message.toString().endsWith("\\")) {
          message.setLength(message.length() - 1);
          message.append('\n').append(reader.readLine("...> "));
          if (message.length() > 16384)
            throw new IllegalArgumentException("Input capacity exceeded");
        }
        history.record(message.toString());
        if (message.toString().stripLeading().startsWith("/")) command(message.toString().strip());
        else send(message.toString());
      } catch (BackendClient.UncertainAcceptanceException uncertain) {
        out(uncertain.getMessage());
      } catch (BackendClient.ApiException rejected) {
        out(rejected.getMessage());
      } catch (InterruptedException interrupted) {
        out(
            "コマンドが割り込みにより中断されました。 原因: "
                + dev.mikoto2000.rei.event.FailureMessages.describe(interrupted));
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        return;
      } catch (UserInterruptException interrupted) {
        out("Ctrl-C により入力を中止しました。依頼は送信していません。");
      } catch (EndOfFileException eof) {
        return;
      } catch (IllegalArgumentException invalid) {
        out(invalid.getMessage());
      } catch (IOException failure) {
        out(
            "Command I/O failed. Inspect the Run or receipt before submitting again. 原因: "
                + dev.mikoto2000.rei.event.FailureMessages.describe(failure));
      }
    }
  }

  /** 軽量CLIでの対話に関する再確認を処理する。 */
  private void refresh() throws IOException, InterruptedException {
    refreshCatalog();
    try {
      currentModel = backend.get("/api/v1/models/current").path("model").asText("unknown");
    } catch (BackendClient.ApiException unavailable) {
      if (unavailable.status() != 404) throw unavailable;
    }
    var projects = backend.get("/api/v1/projects");
    var ids = new ArrayList<String>();
    var registeredPaths = new ArrayList<String>();
    for (var value : projects) {
      ids.add(value.path("id").asText());
      if (value.hasNonNull("path")) registeredPaths.add(value.path("path").asText());
    }
    projectIds = List.copyOf(ids);
    projectPaths = List.copyOf(registeredPaths);
    try {
      allSessionIds = sessionCompletion(null);
    } catch (BackendClient.ApiException unavailable) {
      if (unavailable.status() != 400 && unavailable.status() != 404) throw unavailable;
    }
    if (project != null) sessionIds = sessionCompletion(project);
  }

  /** 軽量CLIでの対話で用いる会話入力補完を返す。 */
  private List<String> sessionCompletion(String project) throws IOException, InterruptedException {
    var ids = new ArrayList<String>();
    var cursors = new HashSet<String>();
    String cursor = null;
    do {
      String path =
          "/api/v1/sessions?limit=100"
              + (project == null ? "" : "&projectId=" + BackendClient.segment(project))
              + (cursor == null ? "" : "&cursor=" + BackendClient.segment(cursor));
      var page = backend.get(path);
      for (var value : page.path("items")) ids.add(value.path("sessionId").asText());
      cursor = page.path("nextCursor").isTextual() ? page.path("nextCursor").asText() : null;
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (cursor != null && (!cursors.add(cursor) || cursors.size() > 128))
        throw new IOException("Session completion pagination capacity exceeded");
    } while (cursor != null);
    return List.copyOf(ids);
  }

  /** 条件に合う対象を利用するため、プロジェクトを選択する。 */
  private void selectProject(String id) throws IOException, InterruptedException {
    JsonNode match = null;
    for (var value : backend.get("/api/v1/projects"))
      if (id.equals(value.path("id").asText())) match = value;
    if (match == null) throw new IllegalArgumentException("Unknown registered Project ID");
    project = id;
    projectName = match.path("name").asText();
    projectDirectory =
        match.path("path").asText().isBlank() ? null : Path.of(match.path("path").asText());
    session = null;
    refresh();
    presentResume(false);
  }

  /** 軽量CLIでの対話に関する存在する再開を処理する。 */
  private void presentResume(boolean force) throws InterruptedException {
    try {
      if (!resumeStartupRead) {
        var startup = backend.get("/api/v1/resume-overview");
        resumeAutoEnabled = startup.path("enabled").asBoolean(true);
        String zone = startup.path("preferredZone").asText("");
        if (!zone.isBlank()) resumeZone = java.time.ZoneId.of(zone);
        if (resumeAutoEnabled) {
          var counts = new ArrayList<dev.mikoto2000.rei.checkpoint.ResumeOverview.ProjectCount>();
          for (var row : startup.path("projects"))
            counts.add(
                BackendClient.JSON.treeToValue(
                    row, dev.mikoto2000.rei.checkpoint.ResumeOverview.ProjectCount.class));
          String summary = dev.mikoto2000.rei.checkpoint.ResumeOverview.summary(counts);
          if (!summary.isBlank()) out(summary);
        }
        resumeStartupRead = true;
      }
      if (project == null || (!force && !resumeAutoEnabled)) return;
      var value =
          backend.get("/api/v1/projects/" + BackendClient.segment(project) + "/resume-overview");
      var overview =
          BackendClient.JSON.treeToValue(value, dev.mikoto2000.rei.checkpoint.ResumeOverview.class);
      if (!project.equals(overview.projectId()))
        throw new IOException("Invalid overview ownership");
      String text = resumeSeen.present(overview, resumeZone, force);
      if (!text.isBlank()) out(text);
    } catch (IOException | RuntimeException unavailable) {
      if (force) out("再開候補の取得に失敗しました。/resume list で確認してください。");
    }
  }

  /** 軽量CLIでの対話に関する送信を処理する。 */
  private void send(String message) throws IOException, InterruptedException {
    send(message, mode);
  }

  /** 軽量CLIでの対話に関する送信を処理する。 */
  private void send(String message, String access) throws IOException, InterruptedException {
    requireProject();
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (message.length() > 16384) throw new IllegalArgumentException("Input capacity exceeded");
    String key = UUID.randomUUID().toString();
    out("Receipt: " + key);
    var accepted = backend.submit(project, session, message, key, access);
    session = accepted.path("sessionId").asText();
    run = accepted.path("runId").asText();
    out("Run: " + run);
    watch(run);
    refresh();
  }

  /** 軽量CLIでの対話で用いる所属プロジェクトの識別子を返す。 */
  String projectId() {
    return project;
  }

  /** 軽量CLIでの対話で用いる会話の識別子を返す。 */
  String sessionId() {
    return session;
  }

  /** 軽量CLIでの対話で用いるコマンド入力の解釈を返す。 */
  private static Parser commandParser() {
    return new dev.mikoto2000.rei.ui.shell.ShellCompletionParser(
        Path.of(System.getProperty("user.home")));
  }

  /** 軽量CLIでの対話で用いるコマンド語句一覧を返す。 */
  static List<String> commandWords(String line) {
    return java.util.Arrays.asList(
        new dev.mikoto2000.rei.core.command.UserInputParser().split(line));
  }

  /** 軽量CLIでの対話に関するコマンドを処理する。 */
  void command(String line) throws IOException, InterruptedException {
    var rawWords = commandWords(line);
    String requestedHelp = catalog.requestedHelp(rawWords);
    if (requestedHelp != null) {
      if (!requestedHelp.isEmpty()) out(requestedHelp);
      return;
    }
    var words = catalog.normalize(rawWords);
    String name = words.getFirst();
    String action = words.size() > 1 ? words.get(1) : "list";
    if (name.equals("/resume") && action.equals("overview") && words.size() == 2) {
      requireProject();
      presentResume(true);
      return;
    }
    if (catalog.requiresSubcommand(words)) {
      out(catalog.help(words));
      return;
    }
    // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
    if (words.size() > 1
        && !words.contains("--")
        && Set.of("--help", "-h").contains(words.getLast())) {
      String help = catalog.help(words.subList(0, words.size() - 1));
      if (!help.isEmpty()) {
        out(help);
        return;
      }
    }
    if (name.equals("/mode") && session == null) {
      requireProject();
      if (action.equals("list") || action.equals("status")) {
        if (words.size() > 2) throw new IllegalArgumentException("Unexpected mode argument");
        out("Response mode: auto (text: normal, voice: conversation); no active Session");
        return;
      }
      // Validate before creating an empty Session. Run access is unchanged.
      RemoteCommands.route(words, project, "validation");
      createSession(null);
    }
    if (name.equals("/llm") && words.size() == 2 && words.get(1).equals("capture")) {
      out(
          "/llm capture next|status|off|list [run-id]|show <attempt-id>|raw <attempt-id>|export <attempt-id> <path>|delete <run-id>|clear");
      return;
    }
    if (name.equals("/llm")
        && words.size() == 3
        && words.get(1).equals("capture")
        && words.get(2).equals("next")
        && session == null) {
      requireProject();
      createSession(null);
    }
    if (name.equals("/approval")) {
      approvalCommand(words);
      return;
    }
    if (RemoteCommands.NAMES.contains(name)
        && !(name.equals("/history") && Set.of("off", "on").contains(action))) {
      var request =
          RemoteCommands.route(
              words,
              project,
              session,
              projectDirectory == null ? Path.of("").toAbsolutePath() : projectDirectory);
      if (Set.of(
              "/api/v1/activity/commands/coaching/settings",
              "/api/v1/activity/commands/coaching/enabled")
          .contains(request.path())) {
        var state = backend.get("/api/v1/activity/coaching");
        if (!state.path("revision").canConvertToLong())
          throw new java.io.IOException("Coaching revision unavailable");
        var body = new LinkedHashMap<String, Object>();
        ((Map<?, ?>) request.body()).forEach((key, value) -> body.put((String) key, value));
        body.put("expectedRevision", state.path("revision").asLong());
        request = new RemoteCommands.Request(request.method(), request.path(), body);
      }
      if (Set.of(
              "/api/v1/google-calendar/authorize",
              "/api/v1/google-calendar/refresh-token",
              "/api/v1/google-tasks/authorize")
          .contains(request.path())) {
        String key = UUID.randomUUID().toString();
        out("Receipt: " + key);
        var accepted =
            switch (request.path()) {
              case "/api/v1/google-calendar/authorize" ->
                  backend.authorizeGoogleCalendar(request.body(), key);
              case "/api/v1/google-calendar/refresh-token" ->
                  backend.refreshGoogleCalendar(request.body(), key);
              default -> backend.authorizeGoogleTasks(request.body(), key);
            };
        session = accepted.path("sessionId").asText();
        run = accepted.path("runId").asText();
        out("Run: " + run);
        watch(run);
        refresh();
        return;
      }
      if (name.equals("/memory") && Set.of("summarize", "consolidate").contains(action)) {
        String key = UUID.randomUUID().toString();
        out("Receipt: " + key);
        var accepted =
            action.equals("summarize")
                ? backend.submitMemorySummary(project, request.body(), key)
                : backend.submitMemoryConsolidation(project, request.body(), key);
        session = accepted.path("sessionId").asText();
        run = accepted.path("runId").asText();
        out("Run: " + run);
        watch(run);
        refresh();
        return;
      }
      if (name.equals("/sleep")
          && request.method().equals("POST")
          && request.path().endsWith("/memory/sleep")) {
        String key = UUID.randomUUID().toString();
        out("Receipt: " + key);
        var accepted = backend.submitMemorySleep(project, request.body(), key);
        session = accepted.path("sessionId").asText();
        run = accepted.path("runId").asText();
        out("Run: " + run);
        watch(run);
        refresh();
        return;
      }
      if (Set.of(
              "/api/v1/diagnostics",
              "/api/v1/document/recovery",
              "/api/v1/repair/recovery",
              "/api/v1/paper/operations",
              "/api/v1/material-reviews",
              "/api/v1/external-agent/operations")
          .contains(request.path())) {
        String key = UUID.randomUUID().toString();
        out("Receipt: " + key);
        var accepted =
            switch (request.path()) {
              case "/api/v1/document/recovery" ->
                  backend.submitDocumentRecovery(request.body(), key);
              case "/api/v1/repair/recovery" -> backend.submitRepairRecovery(request.body(), key);
              case "/api/v1/paper/operations" -> backend.submitPaper(request.body(), key);
              case "/api/v1/material-reviews" -> backend.submitMaterialReview(request.body(), key);
              case "/api/v1/external-agent/operations" ->
                  backend.submitExternalAgent(request.body(), key);
              default -> backend.submitDiagnostic(request.body(), key);
            };
        session = accepted.path("sessionId").asText();
        run = accepted.path("runId").asText();
        out("Run: " + run);
        watch(run);
        refresh();
        return;
      }
      if (name.equals("/llm")
          && (request.path().endsWith("/raw") || request.path().endsWith("/export"))) {
        var attempt = backend.get(request.path().substring(0, request.path().lastIndexOf('/')));
        if (!attempt.path("captureState").asText().equals("COMPLETE"))
          throw new IllegalArgumentException("Complete original unavailable");
        String confirmation =
            reader.readLine("秘密情報を含む未加工本文を表示・保存します。端末履歴・保存後のコピーは削除対象外です。続行しますか? [y/N] ");
        if (!confirmation.equalsIgnoreCase("y") && !confirmation.equalsIgnoreCase("yes")) return;
        var body = new LinkedHashMap<String, Object>((Map<String, Object>) request.body());
        body.put("approved", true);
        var response = backend.post(request.path(), body);
        // Raw is already control-escaped by CaptureDisplay and was explicitly approved. Do not mask
        // its contents again.
        if (request.path().endsWith("/raw")) reader.printAbove(response.path("text").asText());
        else out(response.path("text").asText());
        return;
      }
      var response =
          request.method().equals("GET")
              ? backend.get(request.path())
              : backend.mutation(request.method(), request.path(), request.body());
      if (Set.of(
                  "/search",
                  "/image",
                  "/summarize",
                  "/briefing",
                  "/work",
                  "/resume",
                  "/embed",
                  "/schedule",
                  "/task")
              .contains(name)
          && response.has("runId")) {
        if (name.equals("/embed"))
          out(
              "追加処理を開始: "
                  + String.join(", ", words.subList(action.equals("add") ? 2 : 1, words.size())));
        if (response.path("text").isTextual()) out(response.path("text").asText());
        if (name.equals("/resume")) {
          String resumedSession = response.path("sessionId").asText();
          if (resumedSession.isBlank()) throw new IOException("Resume response has no Session");
          session = resumedSession;
          out("Current session: " + session);
        }
        run = response.path("runId").asText();
        out("Run: " + run);
        watch(run);
        return;
      }
      if (name.equals("/activity") && request.path().endsWith("/suggest-rules")
          || name.equals("/bsky") && request.path().endsWith("/replies")
          || name.equals("/interest") && request.path().endsWith("/discover")
          || name.equals("/feed")
              && Set.of("update", "summary", "item").contains(action)
              && response.has("runId")) {
        run = response.path("runId").asText();
        out("Run: " + run);
        watch(run);
        return;
      }
      if (name.equals("/mode")) {
        String style = response.path("style").asText("AUTO").toLowerCase(Locale.ROOT);
        out(
            "Response mode: "
                + style
                + (style.equals("auto")
                    ? " (text: normal, voice: conversation)"
                    : response.path("voiceOnly").asBoolean()
                        ? " (voice only)"
                        : " (text and voice)"));
        return;
      }
      if (name.equals("/models")) {
        var names = new ArrayList<String>();
        String current = response.path("currentModel").asText();
        currentModel = current;
        for (var model : response.path("models")) {
          String value = model.asText();
          names.add(value);
          out((value.equals(current) ? "* " : "") + value);
        }
        modelIds = List.copyOf(names);
        return;
      }
      if (name.equals("/model")) {
        currentModel = response.path("model").asText();
        out((request.method().equals("GET") ? "" : "current model: ") + currentModel);
        return;
      }
      if (response.isObject() && response.path("text").isTextual())
        out(response.path("text").asText());
      else show(response);
      return;
    }
    switch (name) {
      case "/sh" -> {
        if (words.size() != 1) throw new IllegalArgumentException("Usage: /sh");
        requireProject();
        if (projectDirectory == null)
          throw new IllegalArgumentException("Project directory is unavailable");
        backend.detach();
        attached = false;
        int exit = InteractiveShell.run(terminal, projectDirectory);
        if (exit != 0) out("shell exited with code " + exit);
      }
      case "/help" -> {
        String help = catalog.help(words.subList(1, words.size()));
        out(
            help.isEmpty()
                ? String.join(" ", COMMANDS)
                    + "\n/project list|select ID|register LOCAL_PATH; /session list|select ID|new|end; /watch RUN_ID; /history off|on. Unsupported commands are never sent to the model."
                : help);
      }
      case "/project" -> {
        switch (action) {
          case "list" -> {
            if (words.size() != 2 && words.size() != 1)
              throw new IllegalArgumentException("Usage: /project list");
            for (var value : backend.get("/api/v1/projects"))
              out(
                  (value.path("id").asText().equals(project) ? "* " : "  ")
                      + value.path("path").asText());
            refresh();
          }
          case "select" -> {
            if (words.size() != 3) throw new IllegalArgumentException("Usage: /project select ID");
            selectProject(argument(words, 2));
          }
          case "remove" -> {
            if (words.size() != 3) throw new IllegalArgumentException("Usage: /project remove DIR");
            var target = localProjectPath(words.get(2)).toRealPath();
            for (var value : backend.get("/api/v1/projects")) {
              if (target.toString().equals(value.path("path").asText())) {
                String removed = value.path("id").asText();
                backend.mutation(
                    "DELETE", "/api/v1/projects/" + BackendClient.segment(removed), null);
                if (removed.equals(project)) {
                  var fallback =
                      backend.post(
                          "/api/v1/projects/register",
                          Map.of("path", Path.of("").toAbsolutePath().normalize().toString()));
                  selectProject(fallback.path("id").asText());
                }
                break;
              }
            }
            out("project removed: " + target);
            out(
                "current project: "
                    + (projectDirectory == null ? Path.of("").toAbsolutePath() : projectDirectory));
            refresh();
          }
          case "register", "add", "cd" -> {
            if (words.size() != 3)
              throw new IllegalArgumentException("Usage: /project " + action + " DIR");
            var value =
                backend.post(
                    "/api/v1/projects/register",
                    Map.of("path", localProjectPath(argument(words, 2)).toString()));
            if (!action.equals("add")) {
              boolean changed = !value.path("id").asText().equals(project);
              if (changed || !action.equals("cd")) selectProject(value.path("id").asText());
              else presentResume(false);
              if (action.equals("cd") && changed) {
                var latest =
                    backend
                        .get(
                            "/api/v1/sessions?projectId="
                                + BackendClient.segment(project)
                                + "&limit=1")
                        .path("items");
                if (!latest.isEmpty()) session = latest.get(0).path("sessionId").asText();
              }
            }
            out(
                (action.equals("add") ? "project added: " : "current project: ")
                    + value.path("path").asText());
            refresh();
          }
          default ->
              throw new IllegalArgumentException(
                  "Usage: /project list|add DIR|cd DIR|select ID|register LOCAL_PATH");
        }
      }

      case "/session" -> {
        requireProject();
        String selectedAction = words.size() == 1 ? "show" : action;
        switch (selectedAction) {
          case "list" -> {
            if (words.size() != 2) throw new IllegalArgumentException("Usage: /session list");
            var format = new dev.mikoto2000.rei.conversation.HistoryFormatter();
            String cursor = null;
            boolean any = false;
            var listedIds = new ArrayList<String>();
            do {
              var page =
                  backend.get(
                      "/api/v1/sessions?projectId="
                          + BackendClient.segment(project)
                          + "&limit=100"
                          + (cursor == null ? "" : "&cursor=" + BackendClient.segment(cursor)));
              for (var row : page.path("items")) {
                listedIds.add(row.path("sessionId").asText());
                if (!any) {
                  out("Sessions:");
                  any = true;
                }
                out(
                    (row.path("sessionId").asText().equals(session) ? "* " : "  ")
                        + format.label(row.path("sessionId").asText())
                        + "  "
                        + format.label(row.path("title").asText()));
              }
              String next =
                  page.path("nextCursor").isTextual() ? page.path("nextCursor").asText() : null;
              if (next != null && next.equals(cursor))
                throw new IOException("Session cursor did not advance");
              cursor = next;
            } while (cursor != null);
            if (!any) out("No sessions.");
            sessionIds = List.copyOf(listedIds);
          }
          case "show" -> {
            if (words.size() > 2) throw new IllegalArgumentException("Usage: /session show");
            if (session == null) out("No active session.");
            else {
              var row = backend.get("/api/v1/sessions/" + BackendClient.segment(session));
              var format = new dev.mikoto2000.rei.conversation.HistoryFormatter();
              out("Session:");
              out("  id: " + format.label(row.path("sessionId").asText()));
              out("  title: " + format.label(row.path("title").asText()));
              out("  projectId: " + format.label(row.path("projectId").asText()));
              out("  createdAt: " + row.path("createdAt").asText());
              out("  updatedAt: " + row.path("updatedAt").asText());
            }
          }
          case "select", "switch", "resume" -> {
            if (words.size() != 3)
              throw new IllegalArgumentException("Usage: /session switch SESSION_ID");
            String id = argument(words, 2);
            var value = backend.get("/api/v1/sessions/" + BackendClient.segment(id));
            if (!project.equals(value.path("projectId").asText()))
              throw new IllegalArgumentException(
                  "Session belongs to another project. Select that project first.");
            session = id;
            out(
                "Current session: "
                    + new dev.mikoto2000.rei.conversation.HistoryFormatter().label(id));
          }
          case "new" -> {
            if (words.size() > 3) throw new IllegalArgumentException("Usage: /session new [TITLE]");
            createSession(words.size() == 3 ? words.get(2) : null);
          }
          case "end" -> {
            if (words.size() != 2) throw new IllegalArgumentException("Usage: /session end");
            if (session == null) out("No active session.");
            else {
              backend.post(
                  "/api/v1/sessions/" + BackendClient.segment(session) + "/end",
                  Map.of("projectId", project));
              out(
                  "Session ended: "
                      + new dev.mikoto2000.rei.conversation.HistoryFormatter().label(session));
              session = null;
            }
          }
          default ->
              throw new IllegalArgumentException(
                  "Usage: /session list|show|switch ID|new [TITLE]|end");
        }
        if (!Set.of("list", "show").contains(selectedAction)) refresh();
      }
      case "/chat" -> {
        var input = ChatInput.parse(words, mode);
        if (input.runId() == null) send(input.message(), input.mode());
        else {
          requireProject();
          requireSession();
          show(
              backend.post(
                  "/api/v1/runs/" + BackendClient.segment(input.runId()) + "/input",
                  Map.of("projectId", project, "sessionId", session, "message", input.message())));
        }
      }
      case "/run-mode" -> {
        String selected = argument(words, 1).toUpperCase(Locale.ROOT).replace('-', '_');
        if (!Set.of("EXCLUSIVE", "READ_ONLY", "CONVERSATION").contains(selected))
          throw new IllegalArgumentException("Usage: /run-mode exclusive|read-only|conversation");
        mode = selected;
        out("Run access: " + mode);
      }
      case "/cancel" -> {
        if (words.size() > 2) throw new IllegalArgumentException("Usage: /cancel [RUN_ID]");
        if (words.size() == 1) {
          requireProject();
          backend.post(
              "/api/v1/projects/" + BackendClient.segment(project) + "/runs/cancel", Map.of());
        } else
          show(
              backend.post(
                  "/api/v1/runs/" + BackendClient.segment(argument(words, 1)) + "/cancel",
                  Map.of()));
      }
      case "/watch" -> {
        run = argument(words, 1);
        watch(run);
      }
      case "/runs" -> {
        if (words.size() > 2) throw new IllegalArgumentException("Usage: /runs [RUN_ID]");
        if (words.size() == 2)
          show(backend.get("/api/v1/runs/" + BackendClient.segment(words.get(1))));
        else out(backend.get("/api/v1/runs/active").path("text").asText());
      }
      case "/receipt" -> {
        String key = argument(words, 1);
        if (!key.matches("[A-Za-z0-9._:-]{1,128}"))
          throw new IllegalArgumentException("Invalid receipt key");
        show(backend.get("/api/v1/chat/receipts/" + key));
      }
      case "/history", "/input-history" -> {
        if (!Set.of("off", "on").contains(action))
          throw new IllegalArgumentException("Usage: /input-history off|on");
        recordHistory = action.equals("on");
        history.enabled(recordHistory);
        out("Input history " + action);
      }
      default ->
          throw new IllegalArgumentException(
              "Unsupported CLI command: "
                  + name
                  + ". Use legacy-shell; this input was not sent to the model.");
    }
  }

  /** 軽量CLIでの対話に関する承認コマンドを処理する。 */
  private void approvalCommand(List<String> words) throws IOException, InterruptedException {
    requireProject();
    String action = words.size() == 1 ? "list" : words.get(1);
    String base = "/api/v1/projects/" + BackendClient.segment(project) + "/approvals";
    if (action.equals("list")) {
      // 扱える量を超える要求を検出し、上限に応じた扱いを選ぶ。
      if (words.size() > 2) throw new IllegalArgumentException("Usage: /approval list");
      var rows = backend.get(base);
      if (!rows.isArray() || rows.size() > 256) throw new IOException("Invalid approval list");
      if (rows.isEmpty()) out("有効な承認要求はありません。");
      for (var row : rows) {
        checkApproval(row);
        String number = approvalAliases.bind(project, session, row.path("id").asText());
        printApproval(row, number);
      }
      return;
    }
    boolean continued = words.size() == 4 && words.get(3).equals("--continue");
    if (words.size() != 3 && !continued || continued && !action.equals("approve"))
      throw new IllegalArgumentException("Usage: /approval show|approve|deny ID [--continue]");
    String id = approvalAliases.resolve(project, session, words.get(2));
    String path = base + "/" + BackendClient.segment(id);
    JsonNode row;
    if (action.equals("show")) row = backend.get(path);
    else if (Set.of("approve", "deny", "reject").contains(action)) {
      Map<String, Object> body =
          continued
              ? Map.of("approved", true, "continueRun", true)
              : Map.of("approved", action.equals("approve"));
      row = backend.post(path + "/decision", body);
    } else throw new IllegalArgumentException("Usage: /approval list|show|approve|deny");
    checkApproval(row);
    if (!row.path("id").asText().equals(id))
      throw new IOException("Approval response identity mismatch");
    printApproval(row, id);
    if (continued) {
      run = row.path("runId").asText();
      session = row.path("sessionId").asText();
      out("続行を受け付けました。保存した呼び出しから再開します。");
      watch(run);
    }
  }

  /** 後続の操作を進められるか判断するため、承認を確認する。 */
  private void checkApproval(JsonNode row) throws IOException {
    if (!row.isObject()
        || !row.path("projectId").asText().equals(project)
        || row.path("id").asText().isBlank()
        || row.path("runId").asText().isBlank()
        || row.path("sessionId").asText().isBlank())
      throw new IOException("Invalid approval ownership");
  }

  /** 利用者に結果を伝えるため、承認を出力する。 */
  private void printApproval(JsonNode row, String reference) {
    out(
        "承認 "
            + reference
            + ": "
            + row.path("tool").asText()
            + " ["
            + row.path("status").asText()
            + "]");
    out(row.path("argumentsPreview").asText());
    out(
        "有効期限: "
            + row.path("expiresAt").asText()
            + " / 続行状態: "
            + row.path("continuationState").asText("UNAVAILABLE"));
    out("承認ID: " + row.path("id").asText());
    out("詳細: /approval show " + reference + "　拒否: /approval deny " + reference);
    if (row.path("continuationState").asText().equals("WAITING_APPROVAL"))
      out("承認して続行: /approval approve " + reference + " --continue");
  }

  /** 新たな対象を利用できるよう、会話を作成する。 */
  private void createSession(String title) throws IOException, InterruptedException {
    var body = new LinkedHashMap<String, Object>();
    body.put("projectId", project);
    body.put("title", title);
    var value = backend.post("/api/v1/sessions", body);
    session = value.path("sessionId").asText();
    out("Current session: " + session);
  }

  /** 軽量CLIでの対話に関する監視を処理する。 */
  private void watch(String id) {
    long generation = subscriptionGeneration.incrementAndGet();
    backend.detach();
    if (watching != null) watching.cancel(true);
    attached = true;
    watching =
        streams.submit(
            () -> {
              var sequence = new AtomicLong();
              var eventOutput = new RunEventOutput(this::out, this::approvalNotice);
              int reconnects = 0;
              try {
                while (attached
                    && generation == subscriptionGeneration.get()
                    && !Thread.currentThread().isInterrupted()) {
                  try {
                    backend.events(
                        id,
                        sequence.get(),
                        event -> {
                          if (!attached || generation != subscriptionGeneration.get()) return;
                          try {
                            var value = BackendClient.JSON.readTree(event.data());
                            if (!id.equals(value.path("runId").asText()))
                              throw new IllegalArgumentException("Event Run identity mismatch");
                            sequence.set(event.sequence());
                            eventOutput.accept(
                                event.type(),
                                value.path("payload"),
                                java.time.Instant.parse(value.path("timestamp").asText()));
                            if (event.type().equals("attention.required")
                                && value
                                    .path("payload")
                                    .path("kind")
                                    .asText()
                                    .equals("APPROVAL_REQUIRED")) {
                              var match =
                                  java.util.regex.Pattern.compile(
                                          "(?m)^/approval approve ([0-9a-fA-F-]{36}) --continue$")
                                      .matcher(value.path("payload").path("message").asText());
                              if (match.find())
                                approvalMenu.offer(
                                    value.path("projectId").asText(),
                                    value.path("sessionId").asText(),
                                    match.group(1));
                            }
                            if (Set.of(
                                    "agent.run.completed",
                                    "agent.run.failed",
                                    "agent.run.cancelled")
                                .contains(event.type())) attached = false;
                          } catch (IOException invalid) {
                            throw new IllegalArgumentException("Malformed Run event");
                          }
                        });
                    if (!attached) return;
                  } catch (BackendClient.ApiException error) {
                    if (error.status() == 409) {
                      out(
                          "Event history has a gap; output is incomplete. Fetching Run status and saved Session turns.");
                      poll(id, generation);
                      return;
                    }
                    if (error.status() == 401 || error.status() == 403) {
                      out(
                          "Backend authentication failed: "
                              + dev.mikoto2000.rei.event.FailureMessages.describe(error));
                      return;
                    }
                    out(
                        "Run event stream rejected: "
                            + dev.mikoto2000.rei.event.FailureMessages.describe(error));
                  } catch (InterruptedException interrupt) {
                    // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
                    Thread.currentThread().interrupt();
                    return;
                  } catch (IOException | IllegalArgumentException error) {
                    if (!attached) return;
                    out(
                        "Run event stream interrupted: "
                            + dev.mikoto2000.rei.event.FailureMessages.describe(error));
                  }
                  if (++reconnects > 3) {
                    out(
                        "Stream disconnected. /watch "
                            + id
                            + " reconnects; the Run continues on Backend.");
                    return;
                  }
                  try {
                    Thread.sleep(250L * reconnects);
                  } catch (InterruptedException interrupt) {
                    Thread.currentThread().interrupt();
                    return;
                  }
                }
              } finally {
                if (generation == subscriptionGeneration.get()) eventOutput.flush();
              }
            });
  }

  /** 軽量CLIでの対話に関する状態確認を処理する。 */
  private void poll(String id, long generation) {
    for (int attempt = 0;
        attempt < 60 && attached && generation == subscriptionGeneration.get();
        attempt++) {
      try {
        var status = backend.get("/api/v1/runs/" + BackendClient.segment(id));
        String state = status.path("status").asText();
        if (Set.of("COMPLETED", "FAILED", "CANCELLED", "UNKNOWN").contains(state)) {
          out("Run status: " + state + " (stream output incomplete)");
          String savedSession = status.path("sessionId").asText();
          if (!savedSession.isEmpty())
            show(backend.get("/api/v1/sessions/" + BackendClient.segment(savedSession) + "/turns"));
          return;
        }
        Thread.sleep(1000);
      } catch (InterruptedException interrupted) {
        // 中断の事実を呼び出し元にも伝え、終了やキャンセルの判断を引き継ぐ。
        Thread.currentThread().interrupt();
        return;
      } catch (IOException connection) {
        out(
            "Status polling failed; inspect /runs "
                + id
                + " 原因: "
                + dev.mikoto2000.rei.event.FailureMessages.describe(connection));
        return;
      }
    }
    out("Status polling ended; inspect /runs " + id + ". Backend execution was not cancelled.");
  }

  /** 条件を満たさない操作を拒否するため、プロジェクトの前提条件を確認する。 */
  private void requireProject() {
    if (project == null)
      throw new IllegalArgumentException(
          "Select a Project with /project select ID or /project register LOCAL_PATH");
  }

  /** 条件を満たさない操作を拒否するため、会話の前提条件を確認する。 */
  private void requireSession() {
    if (session == null) throw new IllegalArgumentException("No Session selected");
  }

  /** 軽量CLIでの対話で用いるローカルプロジェクトパスを返す。 */
  private Path localProjectPath(String value) {
    var path = Path.of(value);
    return (path.isAbsolute()
            ? path
            : (projectDirectory == null ? Path.of("").toAbsolutePath() : projectDirectory)
                .resolve(path))
        .toAbsolutePath()
        .normalize();
  }

  /** 軽量CLIでの対話で用いる引数を返す。 */
  private static String argument(List<String> words, int index) {
    if (index >= words.size()) throw new IllegalArgumentException("Missing command argument");
    return words.get(index);
  }

  /** 利用者が対象の情報を確認できるように表示する。 */
  private void show(JsonNode value) throws IOException {
    out(BackendClient.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
  }

  /** 利用先に渡せるよう、プロンプトを組み立てる。 */
  String buildPrompt(java.time.LocalTime time) {
    return time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        + " "
        + safeOutput(currentModel)
        + "> ";
  }

  /** 軽量CLIでの対話で用いる安全性出力を返す。 */
  private String safeOutput(String value) {
    return backend.redact(value).replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]", "");
  }

  /** 軽量CLIでの対話に関する出力先を処理する。 */
  private void out(String value) {
    reader.printAbove(safeOutput(value));
  }

  /** 軽量CLIでの対話に関する承認通知を処理する。 */
  private void approvalNotice(String message) {
    String safe = safeOutput(message);
    if (ApprovalDisplayState.interactive(terminal)) reader.printAbove(ApprovalNotice.format(safe));
    else {
      terminal.writer().println(ApprovalNotice.text(safe));
      terminal.writer().flush();
    }
  }

  /** 軽量CLIでの対話で利用した資源や登録を解放し、利用を終了する。 */
  @Override
  public void close() throws IOException {
    approvalMenu.close();
    attached = false;
    backend.detach();
    streams.shutdownNow();
    history.close();
    terminal.close();
  }
}
