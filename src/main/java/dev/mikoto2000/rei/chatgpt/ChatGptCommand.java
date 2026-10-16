package dev.mikoto2000.rei.chatgpt;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

@Component
@Command(
    name = "chatgpt",
    description = "Sign in with ChatGPT の認証と ChatGPT 利用枠を管理します",
    mixinStandardHelpOptions = true)
public final class ChatGptCommand {
  private final ObjectProvider<PlanOAuth> oauth;
  private final ObjectProvider<PlanClient> client;
  private final ObjectProvider<PlanLogin> login;
  private dev.mikoto2000.rei.core.project.ProjectService projects;
  private dev.mikoto2000.rei.core.policy.ToolPermissionGuard permissions;

  @org.springframework.beans.factory.annotation.Autowired
  public void configureProbe(
      dev.mikoto2000.rei.core.project.ProjectService projects,
      dev.mikoto2000.rei.core.policy.ToolPermissionGuard permissions) {
    this.projects = projects;
    this.permissions = permissions;
  }

  private void authorizeProbe(String account, String model, String task) {
    if (projects == null
        || permissions == null
        || !dev.mikoto2000.rei.event.CredentialRedactor.redact(task).equals(task))
      throw ChatGptException.of(ChatGptException.Code.PERMISSION_DENIED);
    synchronized (projects.currentClient()) {
      var project = projects.currentContext();
      String session = projects.currentSessionId();
      if (session == null) throw ChatGptException.of(ChatGptException.Code.PERMISSION_DENIED);
      var owner =
          new dev.mikoto2000.rei.core.chat.AgentRunContext(
              java.util.UUID.randomUUID().toString(), session, project.root(), project.id());
      String digest =
          dev.mikoto2000.rei.externalagent.ImplementationProposal.sha256(
              (account + "\u0000" + model + "\u0000" + task)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      permissions.check("requestChatGptPlanInference", digest, owner);
    }
  }

  private java.io.PrintWriter shellOutput;

  public void setShellOutput(java.io.PrintWriter output) {
    shellOutput = output;
  }

  private java.io.PrintWriter output() {
    return shellOutput == null ? spec.commandLine().getOut() : shellOutput;
  }

  @Spec CommandSpec spec;

  public ChatGptCommand() {
    this(null, null, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ChatGptCommand(
      ObjectProvider<PlanOAuth> o, ObjectProvider<PlanClient> c, ObjectProvider<PlanLogin> l) {
    oauth = o;
    client = c;
    login = l;
  }

  @Command(name = "login", description = "本人がブラウザで ChatGPT にログインします")
  public int login(@Option(names = "--account", defaultValue = "default") String account) {
    return execute(
        () -> {
          require(login).login(account);
          out(
              "Sign-in validated. Use /chatgpt status and /chatgpt models. ChatGPT plan usage"
                  + " applies.");
        });
  }

  @Command(name = "status")
  public int status() {
    return execute(() -> require(oauth).status().forEach(this::out));
  }

  @Command(name = "models")
  public int models(@Option(names = "--account", defaultValue = "default") String account) {
    return execute(
        () -> require(client).models(account, true).forEach(m -> out(m.id() + " | " + m.name())));
  }

  @Command(name = "logout")
  public int logout(@Option(names = "--account", defaultValue = "default") String account) {
    return execute(
        () -> {
          require(client).stop(account);
          boolean revoked = require(oauth).logout(account);
          require(client).stop(account);
          require(client).invalidate();
          out(
              revoked
                  ? "Signed out; renewable session revoked."
                  : "Signed out locally. Remote revocation was not confirmed; disconnect rei in"
                      + " ChatGPT Settings.");
        });
  }

  @Command(name = "infer", description = "Phase 0 の単発テキスト推論を実行します（ChatGPT 利用枠を消費）")
  public int infer(
      @Option(names = "--account", defaultValue = "default") String account,
      @Option(names = "--model", required = true) String model,
      @Parameters String task) {
    return execute(
        () -> {
          authorizeProbe(account, model, task);
          var result =
              require(client)
                  .infer(
                      account,
                      model,
                      "Return a concise text answer; no tools.",
                      task,
                      java.time.Duration.ofSeconds(120),
                      262144,
                      1048576,
                      () -> Thread.currentThread().isInterrupted());
          out(
              "ChatGPT plan: "
                  + result.state()
                  + "; usage="
                  + result.totalTokens()
                  + "; error="
                  + result.error());
          out("ChatGPT plan output: " + result.diagnostic());
          if (result.state() == PlanSseAssembler.State.COMPLETED) {
            if (result.text().isEmpty()) out("ChatGPT plan: completed without output text");
            else out(dev.mikoto2000.rei.event.CredentialRedactor.redact(result.text()));
          } else
            throw ChatGptException.of(
                result.error() == null ? ChatGptException.Code.PROTOCOL : result.error());
        });
  }

  private <T> T require(ObjectProvider<T> provider) {
    var value = provider == null ? null : provider.getIfAvailable();
    if (value == null)
      throw new IllegalArgumentException("Enable rei.chatgpt-plan.enabled for Windows local use");
    return value;
  }

  private void out(String text) {
    output().println(text);
  }

  private int execute(Runnable operation) {
    try {
      operation.run();
      return 0;
    } catch (ChatGptException e) {
      out(dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return 2;
    } catch (Exception e) {
      out(
          "ChatGPT plan operation unavailable 原因: "
              + dev.mikoto2000.rei.event.FailureMessages.describe(e));
      return 2;
    } finally {
      output().flush();
    }
  }
}
