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
  private final SubAgentRegistry registry;
  private final SubAgentDefinitionLoader loader;
  private final SubAgentToolPolicy policy;
  private SubAgentRunner runner;
  private dev.mikoto2000.rei.core.project.ProjectService projects;

  @org.springframework.beans.factory.annotation.Autowired
  public void configureExecution(
      SubAgentRunner runner, dev.mikoto2000.rei.core.project.ProjectService projects) {
    this.runner = runner;
    this.projects = projects;
  }

  private java.io.PrintWriter shellOutput;
  @Spec CommandSpec spec;

  public SubAgentCommand() {
    this(null, null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SubAgentCommand(
      SubAgentRegistry registry, SubAgentDefinitionLoader loader, SubAgentToolPolicy policy) {
    this.registry = registry;
    this.loader = loader;
    this.policy = policy;
  }

  public Integer call() {
    return execute(
        () -> {
          if (registry.list().isEmpty()) out("SubAgents: none");
          for (var d : registry.list()) out(d.id() + " | " + d.name() + " | " + d.description());
        });
  }

  /** Keep JLine's encoding even when picocli rebinds the shared command's @Spec. */
  public void setShellOutput(java.io.PrintWriter output) {
    this.shellOutput = output;
  }

  private String report(java.util.function.Consumer<SubAgentCommand> action) {
    var text = new java.io.StringWriter();
    var command = new SubAgentCommand(registry, loader, policy);
    command.setShellOutput(new java.io.PrintWriter(text, true));
    action.accept(command);
    return text.toString();
  }

  public String renderList() {
    return report(SubAgentCommand::call);
  }

  public String renderShow(String id) {
    return report(
        parent -> {
          var command = new ShowCommand();
          command.parent = parent;
          command.id = id;
          command.call();
        });
  }

  public String renderReload() {
    return report(
        parent -> {
          var command = new ReloadCommand();
          command.parent = parent;
          command.call();
        });
  }

  public String renderValidate(Path file) {
    return report(
        parent -> {
          var command = new ValidateCommand();
          command.parent = parent;
          command.file = file;
          command.call();
        });
  }

  public String renderInitialize(String id) {
    return report(
        parent -> {
          var command = new InitCommand();
          command.parent = parent;
          command.id = id;
          command.call();
        });
  }

  private java.io.PrintWriter output() {
    return shellOutput == null ? spec.commandLine().getOut() : shellOutput;
  }

  private void out(String text) {
    output().println(text);
  }

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

  @FunctionalInterface
  private interface Action {
    void run() throws Exception;
  }

  @Command(
      name = "run",
      description = "Run a role manually; chatgpt-plan consumes ChatGPT plan usage")
  public static class RunCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;

    @Parameters(index = "0")
    String role;

    @Option(names = "--backend", defaultValue = "local")
    String backend;

    @Option(names = "--model")
    String model;

    @Option(names = "--account", defaultValue = "default")
    String account;

    @Option(names = "--task", required = true)
    String task;

    @Option(names = "--source")
    java.util.List<String> sources = new java.util.ArrayList<>();

    @Option(names = "--expected-result")
    String expected;

    @Option(names = "--context")
    String context;

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

  @Command(name = "list")
  public static class ListCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;

    public Integer call() {
      return parent.call();
    }
  }

  @Command(name = "show")
  public static class ShowCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;

    @Parameters(index = "0")
    String id;

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

  @Command(name = "reload")
  public static class ReloadCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;

    public Integer call() {
      return parent.execute(
          () -> {
            var errors = parent.registry.reload();
            if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
            parent.out("SubAgents reloaded: " + parent.registry.list().size());
          });
    }
  }

  @Command(name = "validate")
  public static class ValidateCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;

    @Parameters(index = "0")
    Path file;

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

  @Command(name = "init")
  public static class InitCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;

    @Parameters(index = "0")
    String id;

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
