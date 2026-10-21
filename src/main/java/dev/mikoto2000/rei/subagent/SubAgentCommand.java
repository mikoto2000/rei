package dev.mikoto2000.rei.subagent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.Spec;

/** サブエージェントの定義を管理しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "subagent",
    description = "サブエージェントの定義を管理します",
    subcommands = {
      SubAgentCommand.ListCommand.class, SubAgentCommand.ShowCommand.class,
          SubAgentCommand.ReloadCommand.class,
      SubAgentCommand.RunCommand.class, SubAgentCommand.ValidateCommand.class,
          SubAgentCommand.InitCommand.class
    })
public class SubAgentCommand implements Callable<Integer> {
  /** 登録台帳。 */
  private final SubAgentRegistry registry;

  /** 読み込みの窓口。 */
  private final SubAgentDefinitionLoader loader;

  /** 判断基準。 */
  private final SubAgentToolPolicy policy;

  /** 実行の担当。 */
  private SubAgentRunner runner;

  /** プロジェクト一覧。 */
  private dev.mikoto2000.rei.core.project.ProjectService projects;

  /** 補助エージェントへの委譲に関する設定実行を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void configureExecution(
      SubAgentRunner runner, dev.mikoto2000.rei.core.project.ProjectService projects) {
    this.runner = runner;
    this.projects = projects;
  }

  /** ターミナルへの表示先。 */
  private java.io.PrintWriter shellOutput;

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** 補助エージェントへの委譲を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public SubAgentCommand() {
    this(null, null, null);
  }

  /** 指定された値や利用先を使って、SubAgentCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public SubAgentCommand(
      SubAgentRegistry registry, SubAgentDefinitionLoader loader, SubAgentToolPolicy policy) {
    this.registry = registry;
    this.loader = loader;
    this.policy = policy;
  }

  /** サブエージェントの定義を管理しますを行う。 */
  public Integer call() {
    return execute(
        () -> {
          if (registry.list().isEmpty()) out("SubAgents: none");
          for (var d : registry.list()) out(d.id() + " | " + d.name() + " | " + d.description());
        });
  }

  /**
   * 利用者へ結果を案内するターミナルの出力先を設定する。
   *
   * <p>Keep JLine's encoding even when picocli rebinds the shared command's @Spec.
   */
  public void setShellOutput(java.io.PrintWriter output) {
    this.shellOutput = output;
  }

  /** 補助エージェントへの委譲で用いるレポートを返す。 */
  private String report(java.util.function.Consumer<SubAgentCommand> action) {
    var text = new java.io.StringWriter();
    var command = new SubAgentCommand(registry, loader, policy);
    command.setShellOutput(new java.io.PrintWriter(text, true));
    action.accept(command);
    return text.toString();
  }

  /** 利用者が内容を確認できるよう、一覧を表示する。 */
  public String renderList() {
    return report(SubAgentCommand::call);
  }

  /** 利用者が内容を確認できるよう、表示を表示する。 */
  public String renderShow(String id) {
    return report(
        parent -> {
          var command = new ShowCommand();
          command.parent = parent;
          command.id = id;
          command.call();
        });
  }

  /** 利用者が内容を確認できるよう、再読み込みを表示する。 */
  public String renderReload() {
    return report(
        parent -> {
          var command = new ReloadCommand();
          command.parent = parent;
          command.call();
        });
  }

  /** 利用者が内容を確認できるよう、検証を表示する。 */
  public String renderValidate(Path file) {
    return report(
        parent -> {
          var command = new ValidateCommand();
          command.parent = parent;
          command.file = file;
          command.call();
        });
  }

  /** 利用者が内容を確認できるよう、初期化を表示する。 */
  public String renderInitialize(String id) {
    return report(
        parent -> {
          var command = new InitCommand();
          command.parent = parent;
          command.id = id;
          command.call();
        });
  }

  /** 補助エージェントへの委譲で用いる出力を返す。 */
  private java.io.PrintWriter output() {
    return shellOutput == null ? spec.commandLine().getOut() : shellOutput;
  }

  /** 補助エージェントへの委譲に関する出力先を処理する。 */
  private void out(String text) {
    output().println(text);
  }

  /** 要求された処理を実行する。 */
  private int execute(Action action) {
    try {
      if (registry == null) throw new IllegalArgumentException("SubAgent runtime unavailable");
      action.run();
      return 0;
    } catch (Exception e) {
      out("[error] " + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return 2;
    } finally {
      output().flush();
    }
  }

  /** 補助エージェントへの委譲に必要な操作を扱う。 */
  @FunctionalInterface
  private interface Action {
    /** 補助エージェントへの委譲の操作を実行する。 */
    void run() throws Exception;
  }

  /** 補助エージェントへの委譲のうち、実行の操作を利用者コマンドとして受け付ける。 */
  @Command(
      name = "run",
      description = "Run a role manually; chatgpt-plan consumes ChatGPT plan usage")
  public static class RunCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SubAgentCommand parent;

    /** 役割。 */
    @Parameters(index = "0")
    String role;

    /** バックエンド。 */
    @Option(names = "--backend", defaultValue = "local")
    String backend;

    /** モデル。 */
    @Option(names = "--model")
    String model;

    /** アカウント。 */
    @Option(names = "--account", defaultValue = "default")
    String account;

    /** タスク。 */
    @Option(names = "--task", required = true)
    String task;

    /** 取得元一覧。 */
    @Option(names = "--source")
    java.util.List<String> sources = new java.util.ArrayList<>();

    /** 期待する内容。 */
    @Option(names = "--expected-result")
    String expected;

    /** 文脈。 */
    @Option(names = "--context")
    String context;

    /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.execute(
          () -> {
            if (parent.runner == null)
              throw new IllegalArgumentException("SubAgent runtime unavailable");
            if (backend.equals("local")) {
              if (model != null)
                throw new IllegalArgumentException(
                    "Local model is selected by the existing role definition");
              var result = parent.runner.run(role, task, context);
              parent.out(
                  result.status()
                      + " | "
                      + dev.mikoto2000.rei.event.CredentialRedactor.redact(result.output()));
              if (result.status() != SubAgentResult.Status.COMPLETED)
                throw new IllegalArgumentException("SubAgent did not complete");
              return;
            }
            if (!java.util.Set.of("chatgpt-plan", "codex-app-server").contains(backend)
                || model == null
                || expected == null
                || context != null)
              throw new IllegalArgumentException(
                  "External backend requires --model and --expected-result; material is supplied by --source");
            var project = parent.projects.currentContext();
            String session;
            synchronized (parent.projects.currentClient()) {
              project = parent.projects.currentContext();
              session = parent.projects.currentSessionId();
            }
            if (session == null)
              throw new IllegalArgumentException("Select a Session before external delegation");
            var owner =
                new dev.mikoto2000.rei.core.chat.AgentRunContext(
                    java.util.UUID.randomUUID().toString(), session, project.root(), project.id());
            var request =
                new ChatGptSubAgentBackend.Request(
                    role, backend, model, account, task, sources, expected);
            var receipt = parent.runner.runManualBackend(request, owner, true, null);
            parent.out(
                "ChatGPT plan usage | "
                    + receipt.backendId()
                    + " | "
                    + receipt.requestId()
                    + " | "
                    + receipt.state()
                    + " | tokens="
                    + receipt.totalTokens()
                    + " | error="
                    + receipt.error());
            if (!receipt.result().output().isBlank())
              parent.out(
                  dev.mikoto2000.rei.event.CredentialRedactor.redact(receipt.result().output()));
            if (receipt.state() != ChatGptSubAgentBackend.State.COMPLETED)
              throw new IllegalArgumentException(
                  "SubAgent did not complete; inspect Run status before retrying");
          });
    }
  }

  /** 補助エージェントへの委譲のうち、一覧の操作を利用者コマンドとして受け付ける。 */
  @Command(name = "list")
  public static class ListCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SubAgentCommand parent;

    /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.call();
    }
  }

  /** 補助エージェントへの委譲のうち、表示の操作を利用者コマンドとして受け付ける。 */
  @Command(name = "show")
  public static class ShowCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SubAgentCommand parent;

    /** 識別子。 */
    @Parameters(index = "0")
    String id;

    /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.execute(
          () -> {
            var d =
                parent
                    .registry
                    .findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown SubAgent: " + id));
            parent.out(
                "id: "
                    + d.id()
                    + "\nname: "
                    + d.name()
                    + "\ndescription: "
                    + d.description()
                    + "\nrequested tools: "
                    + d.requestedTools()
                    + "\neffective tools: "
                    + parent.policy.effectiveTools(d.requestedTools())
                    + "\nmodel: "
                    + (d.model() == null ? "(current chat model)" : d.model())
                    + "\nmaxSteps: "
                    + d.maxSteps()
                    + "\ntimeout: "
                    + d.timeout()
                    + "\nresultSchema: "
                    + (d.resultSchema() == null ? "(envelope only)" : "compiled")
                    + "\nsource config file: "
                    + d.source());
          });
    }
  }

  /** 補助エージェントへの委譲のうち、再読み込みの操作を利用者コマンドとして受け付ける。 */
  @Command(name = "reload")
  public static class ReloadCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SubAgentCommand parent;

    /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.execute(
          () -> {
            var errors = parent.registry.reload();
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
            parent.out("SubAgents reloaded: " + parent.registry.list().size());
          });
    }
  }

  /** 補助エージェントへの委譲のうち、検証の操作を利用者コマンドとして受け付ける。 */
  @Command(name = "validate")
  public static class ValidateCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SubAgentCommand parent;

    /** ファイル。 */
    @Parameters(index = "0")
    Path file;

    /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.execute(
          () -> {
            var d = parent.loader.load(file);
            parent.out(
                "valid: "
                    + d.id()
                    + "\nprompt: valid\nmaxSteps: "
                    + d.maxSteps()
                    + "\ntimeout: "
                    + d.timeout());
            for (String tool : d.requestedTools()) parent.out(tool + ": allowed");
          });
    }
  }

  /** 補助エージェントへの委譲のうち、初期化の操作を利用者コマンドとして受け付ける。 */
  @Command(name = "init")
  public static class InitCommand implements Callable<Integer> {
    /** 親。 */
    @ParentCommand SubAgentCommand parent;

    /** 識別子。 */
    @Parameters(index = "0")
    String id;

    /** 補助エージェントへの委譲の要求を実行し、呼び出し元へ結果を返す。 */
    public Integer call() {
      return parent.execute(
          () -> {
            if (!id.matches("[a-z][a-z0-9-]{0,63}"))
              throw new IllegalArgumentException("id: expected [a-z][a-z0-9-]{0,63}");
            Path directory = parent.registry.directory();
            Files.createDirectories(directory);
            Path target = directory.resolve(id + ".yaml").normalize();
            if (!target.getParent().equals(directory))
              throw new IllegalArgumentException("id: invalid path");
            Files.writeString(
                target,
                """
          id: %s
          name: %s
          description: TODO
          systemPrompt: |
            TODO
          tools: []
          maxSteps: 10
          timeout: %s
          """
                    .formatted(id, id, id.equals("reviewer") ? "30m" : "120s"),
                StandardOpenOption.CREATE_NEW);
            parent.out("Created: " + target + " (edit, validate, then reload)");
          });
    }
  }
}
