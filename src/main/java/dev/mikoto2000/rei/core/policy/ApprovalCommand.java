package dev.mikoto2000.rei.core.policy;

import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.output.CommandOutput;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** ツールの一回限りの実行承認を確認・判断しますを行うための利用者コマンド。 */
@Component
@Command(name = "approval", description = "ツールの一回限りの実行承認を確認・判断します")
public class ApprovalCommand implements java.util.concurrent.Callable<Integer> {
  /** 保存と検索の窓口。 */
  private final Function<String, List<ToolApprovalRepository.Request>> listPending;

  private final BiFunction<String, String, ToolApprovalRepository.Request> findApproval;
  private final Function<DecisionTarget, ToolApprovalRepository.Request> decideApproval;

  /** プロジェクト一覧。 */
  private final Supplier<String> currentProjectId;

  /** 継続入力一覧。 */
  private Function<String, List<ToolApprovalRepository.Request>> listContinuations;

  private Function<DecisionTarget, ToolApprovalRepository.Request> decideContinuation;
  private Function<String, Optional<String>> continuationState = id -> Optional.empty();

  /** ツール利用の権限制御で使用する継続入力一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setContinuations(ApprovalContinuationService service) {
    this.listContinuations = service == null ? null : service::list;
    this.decideContinuation =
        service == null
            ? null
            : decision ->
                service.decide(
                    decision.project(), decision.id(), decision.approved(), decision.continueRun());
    this.continuationState = service == null ? id -> Optional.empty() : service::state;
  }

  /** continue実行。 */
  @Option(names = "--continue", description = "承認した正確な呼び出しから続行します")
  boolean continueRun;

  /**
   * ツール利用の権限制御で受け渡す別名を表す。
   *
   * @param project プロジェクト
   * @param session 会話
   * @param id 識別子
   */
  private record Alias(String project, String session, String id) {}

  /** 別名一覧。 */
  private final java.util.Map<Object, java.util.Map<String, Alias>> aliases =
      new java.util.WeakHashMap<>();

  /** 再開用の記録一覧。 */
  private Function<ToolApprovalRepository.Request, Optional<CheckpointHint>> findCheckpoint =
      request -> Optional.empty();

  /** ツール利用の権限制御で使用する再開用の記録一覧を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setCheckpoints(
      dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints) {
    this.findCheckpoint =
        request ->
            checkpoints == null
                ? Optional.empty()
                : checkpoints
                    .findByRun(request.projectId(), request.runId())
                    .filter(state -> state.sessionId().equals(request.sessionId()))
                    .map(state -> new CheckpointHint(state.taskId(), state.status()));
  }

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 操作。 */
  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "list",
      paramLabel = "list|show|approve|deny",
      completionCandidates = ActionCandidates.class)
  String action;

  /** 識別子。 */
  @Parameters(index = "1", arity = "0..1", paramLabel = "approvalId")
  String id;

  /** 出力。 */
  private CommandOutput output;

  /** ツール利用の権限制御を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ApprovalCommand() {
    this(null, null);
  }

  /** 指定された値や利用先を使って、ApprovalCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ApprovalCommand(ToolApprovalRepository repository, ProjectService projects) {
    this.listPending = project -> repository.list(project);
    this.findApproval = (project, id) -> repository.get(project, id);
    this.decideApproval =
        decision -> repository.decide(decision.project(), decision.id(), decision.approved());
    this.currentProjectId = () -> projects.currentContext().id();
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = CommandOutput.from(output);
  }

  /** ツールの一回限りの実行承認を確認・判断しますを行う。 */
  @Override
  public Integer call() {
    var writer = output == null ? CommandOutput.from(spec.commandLine().getOut()) : output;
    try {
      String project = currentProjectId.get();
      if (continueRun && !"approve".equals(action))
        throw new IllegalArgumentException("--continue is only valid with approve");
      Object result =
          switch (action) {
            case "list" ->
                listContinuations == null
                    ? listPending.apply(project)
                    : listContinuations.apply(project);
            case "show" -> findApproval.apply(project, requiredId());
            case "approve" -> decide(project, true, continueRun);
            case "deny" -> decide(project, false, false);
            default ->
                throw new IllegalArgumentException(
                    "Use /approval list|show|approve|deny [approvalId]");
          };
      if (result instanceof java.util.List<?> rows) {
        if (rows.isEmpty()) writer.println("有効な承認要求はありません。");
        for (var row : rows)
          if (row instanceof ToolApprovalRepository.Request request)
            printRequest(writer, request, true);
      } else if (result instanceof ToolApprovalRepository.Request request) {
        printRequest(writer, request, false);
        if (continueRun)
          writer.println(
              "続行受付: "
                  + continuationState.apply(request.id()).orElse("UNKNOWN")
                  + "（重複操作でも二重実行しません）。");
        else if (!"deny".equals(action)) printResume(writer, request);
      } else writer.println(result);
      return 0;
    } catch (RuntimeException error) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    } finally {
      writer.flush();
    }
  }

  /** ツール利用の権限制御で用いる必須継続入力一覧を返す。 */
  private ToolApprovalRepository.Request decide(
      String project, boolean approved, boolean continueRequested) {
    var operation = decideContinuation;
    if (continueRequested && operation == null)
      throw new IllegalStateException("Approval continuation unavailable");
    var target = new DecisionTarget(project, requiredId(), approved, continueRequested);
    return operation == null ? decideApproval.apply(target) : operation.apply(target);
  }

  /** ツール利用の権限制御で用いるクライアントキーを返す。 */
  private Object clientKey() {
    var key = dev.mikoto2000.rei.core.project.ProjectClientScope.identity();
    return key == null ? this : key;
  }

  /** ツール利用の権限制御で用いる数値を返す。 */
  private String number(ToolApprovalRepository.Request request) {
    synchronized (aliases) {
      var rows = aliases.computeIfAbsent(clientKey(), ignored -> new java.util.LinkedHashMap<>());
      for (var entry : rows.entrySet())
        if (entry.getValue().project().equals(request.projectId())
            && entry.getValue().id().equals(request.id())
            && java.util.Objects.equals(
                entry.getValue().session(), ProjectService.selectedShellSession()))
          return entry.getKey();
      if (rows.size() >= 1024) return request.id();
      String number = Integer.toString(rows.size() + 1);
      rows.put(
          number,
          new Alias(request.projectId(), ProjectService.selectedShellSession(), request.id()));
      return number;
    }
  }

  /** 利用者に結果を伝えるため、要求を出力する。 */
  private void printRequest(
      CommandOutput writer, ToolApprovalRepository.Request request, boolean numbered) {
    String reference = numbered ? number(request) : request.id();
    writer.println("承認 " + reference + ": " + request.tool() + " [" + request.status() + "]");
    writer.println(request.argumentsPreview());
    writer.println(
        "有効期限: "
            + request
                .expiresAt()
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")));
    writer.println("承認ID: " + request.id());
    if (continuationState.apply(request.id()).isPresent()) {
      writer.println("続行状態: " + continuationState.apply(request.id()).orElseThrow());
      if (java.util.Set.of("PENDING", "APPROVED").contains(request.status())
          && continuationState.apply(request.id()).orElse("").equals("WAITING_APPROVAL"))
        writer.println("承認して続行: /approval approve " + reference + " --continue");
    }
    if (numbered)
      writer.println("詳細: /approval show " + reference + "　拒否: /approval deny " + reference);
  }

  /** 利用者に結果を伝えるため、再開を出力する。 */
  private void printResume(CommandOutput writer, ToolApprovalRepository.Request request) {
    if (request.tool().startsWith("secretaryAuto:")
        || request.tool().startsWith("secretaryAction:")) {
      writer.println("秘書の準備許可です。/secretary orchestrator-status --project PROJECT で状態を確認してください。");
      return;
    }
    try {
      var task = findCheckpoint.apply(request);
      if (task.isEmpty()) {
        writer.println("対応する task ID を取得できません。/resume list で確認してください（承認IDは /resume に使用できません）。");
        return;
      }
      var state = task.get();
      writer.println("task ID: " + state.taskId());
      if (continuationState.apply(request.id()).isPresent()) {
        writer.println("保存した呼び出しから続行: /approval approve " + request.id() + " --continue");
        return;
      }
      if (java.util.Set.of("PENDING", "APPROVED").contains(request.status())
          && !java.util.Set.of("COMPLETED", "ABANDONED", "RUNNING").contains(state.status())) {
        writer.println("再開コマンド: /resume " + state.taskId());
        if ("APPROVED".equals(request.status()))
          writer.println("承認は保存済みです。続行するには上の再開コマンドを実行してください。");
        else writer.println("承認後に上の再開コマンドを実行してください。");
      } else
        writer.println(
            "現在のタスク状態: "
                + state.status()
                + "、承認状態: "
                + request.status()
                + "。/resume list で確認してください。");
    } catch (RuntimeException unavailable) {
      // A hint lookup failure must not make a successfully saved approval look unsuccessful.
      writer.println(
          "task ID の照合に失敗しました。/resume list で確認してください。 原因: "
              + dev.mikoto2000.rei.event.FailureMessages.describe(unavailable));
    }
  }

  /** ツール利用の権限制御で用いる必須識別子を返す。 */
  private String requiredId() {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("approvalId is required");
    if (!id.matches("[0-9]+")) return id;
    synchronized (aliases) {
      var item = aliases.getOrDefault(clientKey(), java.util.Map.of()).get(id);
      if (item == null
          || !item.project().equals(currentProjectId.get())
          || !java.util.Objects.equals(item.session(), ProjectService.selectedShellSession()))
        throw new IllegalArgumentException(
            "表示した承認番号が見つかりません。現在のProject/Sessionで /approval list を実行してください。");
      return item.id();
    }
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super("list", "show", "approve", "deny");
    }
  }

  private record CheckpointHint(String taskId, String status) {}

  private record DecisionTarget(String project, String id, boolean approved, boolean continueRun) {}
}
