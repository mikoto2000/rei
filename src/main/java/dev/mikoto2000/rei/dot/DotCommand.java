package dev.mikoto2000.rei.dot;

import dev.mikoto2000.rei.core.project.ProjectService;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** てんてんへの永続依頼を作成・確認・取消を行うための利用者コマンド。 */
@Component
@Command(name = "dot", description = "てんてんへの永続依頼を作成・確認・取消", mixinStandardHelpOptions = true)
public final class DotCommand implements java.util.concurrent.Callable<Integer> {
  /** サービス。 */
  private final DotDelegationService service;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 引数。 */
  @Parameters(
      arity = "0..*",
      paramLabel = "ACTION [ID|INSTRUCTION] [OPTIONS]",
      completionCandidates = ActionCandidates.class)
  String[] arguments;

  /** 所属プロジェクトの識別子。 */
  @Option(names = "--project-id")
  String projectId;

  /** creation識別子。 */
  @Option(names = "--request-id")
  String creationId;

  /** 分類。 */
  @Option(names = "--category")
  String category;

  /** 文脈。 */
  @Option(names = "--context")
  String context;

  /** 期待する内容。 */
  @Option(names = "--expected-output")
  String expected;

  /** 制約一覧。 */
  @Option(names = "--constraint")
  List<String> constraints;

  /** 判断基準一覧。 */
  @Option(names = "--acceptance-criterion")
  List<String> criteria;

  /** タイムアウト。 */
  @Option(names = "--timeout")
  Integer timeout;

  /** レビュー状態。 */
  @Option(names = "--status")
  String reviewStatus;

  /** 補足。 */
  @Option(names = "--note")
  String note;

  /** 条件を満たしたかどうか。 */
  @Option(names = "--satisfied")
  String satisfied;

  /** 確認済み。 */
  @Option(names = "--yes")
  boolean confirmed;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 利用者の状態表示で用いる語句一覧を返す。 */
  private List<String> words() {
    var out = new ArrayList<String>(arguments == null ? List.of() : Arrays.asList(arguments));
    String[][] options = {
      {"--project-id", projectId},
      {"--request-id", creationId},
      {"--category", category},
      {"--context", context},
      {"--expected-output", expected},
      {"--timeout", timeout == null ? null : timeout.toString()},
      {"--status", reviewStatus},
      {"--note", note},
      {"--satisfied", satisfied}
    };
    for (var option : options)
      if (option[1] != null) {
        out.add(option[0]);
        out.add(option[1]);
      }
    if (constraints != null)
      for (String value : constraints) {
        out.add("--constraint");
        out.add(value);
      }
    if (criteria != null)
      for (String value : criteria) {
        out.add("--acceptance-criterion");
        out.add(value);
      }
    if (confirmed) out.add("--yes");
    return out;
  }

  /** 待機していた作業を再開する窓口。 */
  private DotResumeService resumes;

  /** 利用者の状態表示で使用するResumesを設定する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void setResumes(DotResumeService resumes) {
    this.resumes = resumes;
  }

  /** 出力。 */
  private PrintWriter output;

  /** 利用者の状態表示を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DotCommand() {
    this(null, null);
  }

  /** 指定された値や利用先を使って、DotCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public DotCommand(DotDelegationService service, ProjectService projects) {
    this.service = service;
    this.projects = projects;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(PrintWriter output) {
    this.output = output;
  }

  /** てんてんへの永続依頼を作成・確認・取消を行う。 */
  public Integer call() {
    var out = output == null ? spec.commandLine().getOut() : output;
    try {
      var request = DotCommandRequest.parse(words());
      String project =
          request.projectId() == null ? projects.currentContext().id() : request.projectId();
      Object result =
          switch (request.action()) {
            case "request" ->
                service.createHuman(project, projects.currentSessionId(), request.payload());
            case "requests" -> service.list(project);
            case "status" ->
                resumes == null
                    ? service.get(project, request.requestId())
                    : resumes.status(project, request.requestId());
            case "result" -> service.result(project, request.requestId());
            case "cancel" -> service.cancel(project, request.requestId());
            case "verify" -> resumes.verify(project, request.requestId(), request.payload());
            case "resume" -> resumes.resume(project, request.requestId(), request.payload());
            default -> throw new DotException("INVALID_ARGUMENT");
          };
      out.println(dev.mikoto2000.rei.storage.StorageDatabase.JSON.writeValueAsString(result));
      return 0;
    } catch (Exception e) {
      out.println(
          "[error] "
              + (e instanceof DotException dot ? dot.code : "INVALID_ARGUMENT")
              + ": "
              + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return 2;
    } finally {
      out.flush();
    }
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super("request", "requests", "status", "result", "cancel", "verify", "resume");
    }
  }
}
