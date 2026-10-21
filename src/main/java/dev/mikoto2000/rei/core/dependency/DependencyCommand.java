package dev.mikoto2000.rei.core.dependency;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.event.CredentialRedactor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** 保存された依存関係の監視を確認・キャンセル・回答しますを行うための利用者コマンド。 */
@Component
@Command(name = "dependency", description = "保存された依存関係の監視を確認・キャンセル・回答します")
public class DependencyCommand implements java.util.concurrent.Callable<Integer> {
  /** リポジトリ。 */
  private final PersistentDependencyRepository repo;

  /** サービス。 */
  private final DependencyObservationService service;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 利用条件の確認。 */
  private final ToolPermissionGuard guard;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 操作。 */
  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "list",
      completionCandidates = ActionCandidates.class)
  String action;

  /** 識別子。 */
  @Parameters(index = "1", arity = "0..1")
  String id;

  /** 値。 */
  @Option(names = "--value", description = "ユーザー本人の回答（LLM ツールからは指定できません）")
  String value;

  /** 出力。 */
  private java.io.PrintWriter output;

  /** 外部条件の待機を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public DependencyCommand() {
    this(null, null, null, null);
  }

  /** 指定された値や利用先を使って、DependencyCommandを初期化する。 */
  @Autowired
  public DependencyCommand(
      PersistentDependencyRepository repo,
      DependencyObservationService service,
      ProjectService projects,
      ToolPermissionGuard guard) {
    this.repo = repo;
    this.service = service;
    this.projects = projects;
    this.guard = guard;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = output;
  }

  /** 保存された依存関係の監視を確認・キャンセル・回答しますを行う。 */
  @Override
  public Integer call() {
    var writer = output == null ? spec.commandLine().getOut() : output;
    try {
      var project = projects.currentContext();
      Object result;
      if (action.equals("list")) result = repo.list(project.id());
      else {
        if (id == null || id.isBlank())
          throw new IllegalArgumentException("Dependency ID required");
        var entry = repo.get(project.id(), id);
        var owner =
            new AgentRunContext(entry.id(), entry.sessionId(), project.root(), project.id());
        result =
            switch (action) {
              case "show" -> entry;
              case "history" -> repo.history(project.id(), id);
              case "check" -> {
                boolean network = entry.spec().network();
                guard.check(
                    network ? "checkHttpDependency" : "checkDependency",
                    "{\"dependencyId\":\"" + entry.id() + "\"}",
                    owner);
                yield service.inspect(project.id(), id, network);
              }
              case "cancel" -> {
                yield repo.cancel(project.id(), id);
              }
              case "answer" -> {
                repo.answer(project.id(), id, value);
                yield service.inspect(project.id(), id, false);
              }
              default ->
                  throw new IllegalArgumentException(
                      "Use /dependency list|show|history|check|cancel|answer ID [--value answer]");
            };
      }
      service.flushFacts();
      writer.println(CredentialRedactor.redact(String.valueOf(result)));
      return 0;
    } catch (RuntimeException error) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    } finally {
      writer.flush();
    }
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super("list", "show", "history", "check", "cancel", "answer");
    }
  }
}
