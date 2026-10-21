package dev.mikoto2000.rei.attention;

import dev.mikoto2000.rei.core.project.ProjectService;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** 保存された注意事項を確認・確認済みにしますを行うための利用者コマンド。 */
@Component
@Command(name = "attention", description = "保存された注意事項を確認・確認済みにします")
public class AttentionCommand implements java.util.concurrent.Callable<Integer> {
  /** 保存と検索の窓口。 */
  private final AttentionRepository repository;

  /** プロジェクト一覧。 */
  private final ProjectService projects;

  /** 仕様。 */
  @Spec picocli.CommandLine.Model.CommandSpec spec;

  /** 操作。 */
  @Parameters(
      index = "0",
      arity = "0..1",
      defaultValue = "list",
      paramLabel = "list|show|ack|delivery|deliver|retry-delivery",
      completionCandidates = ActionCandidates.class)
  String action;

  /** 識別子。 */
  @Parameters(index = "1", arity = "0..1", paramLabel = "attentionId")
  String id;

  /** 出力。 */
  private java.io.PrintWriter output;

  /** 配信。 */
  private AttentionDeliveryService delivery;

  /** 重複の有無リスク。 */
  @Option(
      names = "--acknowledge-duplicate-risk",
      description = "前回の配信が成功している可能性があるため、通知が重複するリスクを明示的に承認します")
  boolean duplicateRisk;

  /** 注意の管理で使用する配信を設定する。 */
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  public void setDelivery(AttentionDeliveryService delivery) {
    this.delivery = delivery;
  }

  /** 注意の管理を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public AttentionCommand() {
    this(null, null);
  }

  /** 指定された値や利用先を使って、AttentionCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public AttentionCommand(AttentionRepository repository, ProjectService projects) {
    this.repository = repository;
    this.projects = projects;
  }

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    this.output = output;
  }

  /** 保存された注意事項を確認・確認済みにしますを行う。 */
  @Override
  public Integer call() {
    var writer = output == null ? spec.commandLine().getOut() : output;
    try {
      String project = projects.currentContext().id();
      Object result =
          switch (action) {
            case "list" -> repository.list(project);
            case "show" -> repository.get(project, requiredId());
            case "ack" -> {
              repository.acknowledge(project, requiredId());
              yield "Acknowledged. This does not approve, resume or execute the task.";
            }
            case "delivery" -> requireDelivery().status(project, requiredId());
            case "deliver" -> requireDelivery().request(project, requiredId());
            case "retry-delivery" -> requireDelivery().retry(project, requiredId(), duplicateRisk);
            default ->
                throw new IllegalArgumentException(
                    "Use /attention list|show|ack|delivery|deliver|retry-delivery [attentionId]");
          };
      writer.println(dev.mikoto2000.rei.event.CredentialRedactor.redact(String.valueOf(result)));
      return 0;
    } catch (RuntimeException error) {
      writer.println("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(error));
      return 2;
    } finally {
      writer.flush();
    }
  }

  /** 注意の管理で用いる必須識別子を返す。 */
  private String requiredId() {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("attentionId is required");
    return id;
  }

  /** 条件を満たさない操作を拒否するため、配信の前提条件を確認する。 */
  private AttentionDeliveryService requireDelivery() {
    if (delivery == null) throw new IllegalArgumentException("Attention delivery is unavailable");
    return delivery;
  }

  /** 操作で利用できる入力候補を提供する。 */
  public static final class ActionCandidates
      extends dev.mikoto2000.rei.core.completion.FirstArgumentCandidates {
    /** 既定の状態でActionCandidatesを初期化する。 */
    public ActionCandidates() {
      super("list", "show", "ack", "delivery", "deliver", "retry-delivery");
    }
  }
}
