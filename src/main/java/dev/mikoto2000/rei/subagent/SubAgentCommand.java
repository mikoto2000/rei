package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.util.concurrent.Callable;
import org.springframework.stereotype.Component;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;

@Component
@Command(name = "subagent", description = "SubAgent definitions", subcommands = {
    SubAgentCommand.ListCommand.class, SubAgentCommand.ShowCommand.class, SubAgentCommand.ReloadCommand.class,
    SubAgentCommand.ValidateCommand.class, SubAgentCommand.InitCommand.class })
public class SubAgentCommand implements Callable<Integer> {
  private final SubAgentRegistry registry;
  private final SubAgentDefinitionLoader loader;
  private final SubAgentToolPolicy policy;
  private java.io.PrintWriter shellOutput;
  @Spec CommandSpec spec;
  public SubAgentCommand() { this(null, null, null); }
  @org.springframework.beans.factory.annotation.Autowired
  public SubAgentCommand(SubAgentRegistry registry, SubAgentDefinitionLoader loader, SubAgentToolPolicy policy) {
    this.registry = registry; this.loader = loader; this.policy = policy;
  }
  public Integer call() { return execute(() -> {
    if (registry.list().isEmpty()) out("SubAgents: none");
    for (var d : registry.list()) out(d.id() + " | " + d.name() + " | " + d.description());
  }); }
  /** Keep JLine's encoding even when picocli rebinds the shared command's @Spec. */
  public void setShellOutput(java.io.PrintWriter output) { this.shellOutput = output; }
  private java.io.PrintWriter output() { return shellOutput == null ? spec.commandLine().getOut() : shellOutput; }
  private void out(String text) { output().println(text); }
  private int execute(Action action) {
    try {
      if (registry == null) throw new IllegalArgumentException("SubAgent runtime unavailable");
      action.run(); return 0;
    } catch (Exception e) {
      out("[error] " + dev.mikoto2000.rei.event.CredentialRedactor.redact(e.getMessage())); return 2;
    } finally { output().flush(); }
  }
  @FunctionalInterface private interface Action { void run() throws Exception; }
  @Command(name = "list") public static class ListCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;
    public Integer call() { return parent.call(); }
  }
  @Command(name = "show") public static class ShowCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;
    @Parameters(index = "0") String id;
    public Integer call() { return parent.execute(() -> {
      var d = parent.registry.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown SubAgent: " + id));
      parent.out("id: " + d.id() + "\nname: " + d.name() + "\ndescription: " + d.description()
          + "\nrequested tools: " + d.requestedTools() + "\neffective tools: " + parent.policy.effectiveTools(d.requestedTools())
          + "\nmodel: " + (d.model() == null ? "(current chat model)" : d.model()) + "\nmaxSteps: " + d.maxSteps()
          + "\ntimeout: " + d.timeout() + "\nsource config file: " + d.source());
    }); }
  }
  @Command(name = "reload") public static class ReloadCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;
    public Integer call() { return parent.execute(() -> {
      var errors = parent.registry.reload();
      if (!errors.isEmpty()) throw new IllegalArgumentException(String.join("\n", errors));
      parent.out("SubAgents reloaded: " + parent.registry.list().size());
    }); }
  }
  @Command(name = "validate") public static class ValidateCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;
    @Parameters(index = "0") Path file;
    public Integer call() { return parent.execute(() -> {
      var d = parent.loader.load(file);
      parent.out("valid: " + d.id() + "\nprompt: valid\nmaxSteps: " + d.maxSteps() + "\ntimeout: " + d.timeout());
      for (String tool : d.requestedTools()) parent.out(tool + ": allowed");
    }); }
  }
  @Command(name = "init") public static class InitCommand implements Callable<Integer> {
    @ParentCommand SubAgentCommand parent;
    @Parameters(index = "0") String id;
    public Integer call() { return parent.execute(() -> {
      if (!id.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("id: expected [a-z][a-z0-9-]{0,63}");
      Path directory = parent.registry.directory();
      Files.createDirectories(directory);
      Path target = directory.resolve(id + ".yaml").normalize();
      if (!target.getParent().equals(directory)) throw new IllegalArgumentException("id: invalid path");
      Files.writeString(target, """
          id: %s
          name: %s
          description: TODO
          systemPrompt: |
            TODO
          tools: []
          maxSteps: 10
          timeout: 120s
          """.formatted(id, id), StandardOpenOption.CREATE_NEW);
      parent.out("Created: " + target + " (edit, validate, then reload)");
    }); }
  }
}
