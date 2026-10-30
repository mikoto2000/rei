package dev.mikoto2000.rei.chatgpt;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/** Sign in with ChatGPT の認証と ChatGPT 利用枠を管理しますを行うための利用者コマンド。 */
@Component
@Command(
    name = "chatgpt",
    description = "Sign in with ChatGPT の認証と ChatGPT 利用枠を管理します",
    mixinStandardHelpOptions = true)
public final class ChatGptCommand {
  /** OAuth認証。 */
  private final ObjectProvider<PlanOAuth> oauth;

  /** クライアント。 */
  private final ObjectProvider<PlanClient> client;

  /** ログイン。 */
  private final ObjectProvider<PlanLogin> login;

  /** プロジェクト一覧。 */
  private dev.mikoto2000.rei.core.project.ProjectService projects;

  /** 権限一覧。 */
  private dev.mikoto2000.rei.core.policy.ToolPermissionGuard permissions;

  /** ChatGPTとの連携に関する設定利用可否の確認を処理する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public void configureProbe(
      dev.mikoto2000.rei.core.project.ProjectService projects,
      dev.mikoto2000.rei.core.policy.ToolPermissionGuard permissions) {
    this.projects = projects;
    this.permissions = permissions;
  }

  /** ChatGPTとの連携に関する権限の確認利用可否の確認を処理する。 */
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

  /** ターミナルへの表示先。 */
  private java.io.PrintWriter shellOutput;

  /** 利用者へ結果を案内するターミナルの出力先を設定する。 */
  public void setShellOutput(java.io.PrintWriter output) {
    shellOutput = output;
  }

  /** ChatGPTとの連携で用いる出力を返す。 */
  private java.io.PrintWriter output() {
    return shellOutput == null ? spec.commandLine().getOut() : shellOutput;
  }

  /** 仕様。 */
  @Spec CommandSpec spec;

  /** ChatGPTとの連携を利用するため、指定を補って共通の初期化へ引き渡す。 */
  public ChatGptCommand() {
    this(null, null, null);
  }

  /** 指定された値や利用先を使って、ChatGptCommandを初期化する。 */
  @org.springframework.beans.factory.annotation.Autowired
  public ChatGptCommand(
      ObjectProvider<PlanOAuth> o, ObjectProvider<PlanClient> c, ObjectProvider<PlanLogin> l) {
    oauth = o;
    client = c;
    login = l;
  }

  /** ChatGPTとの連携で用いるログインを返す。 */
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

  /** 対象の現在の状態を、照会用の情報として返す。 */
  @Command(name = "status", description = "ChatGPT アカウントのログイン状態を表示します")
  public int status() {
    return execute(() -> require(oauth).status().forEach(this::out));
  }

  /** ChatGPTとの連携で用いるモデル一覧を返す。 */
  @Command(name = "models", description = "指定アカウントの ChatGPT 利用枠で使えるモデルを一覧表示します")
  public int models(@Option(names = "--account", defaultValue = "default") String account) {
    return execute(
        () -> require(client).models(account, true).forEach(m -> out(m.id() + " | " + m.name())));
  }

  /** ChatGPTとの連携で用いるログアウトを返す。 */
  @Command(name = "logout", description = "指定アカウントからログアウトし、保存済み認証情報を削除します")
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

  /** ChatGPTとの連携で用いる推定を返す。 */
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

  /** 条件を満たさない操作を拒否するため、チャットGPTコマンドの前提条件を確認する。 */
  private <T> T require(ObjectProvider<T> provider) {
    var value = provider == null ? null : provider.getIfAvailable();
    if (value == null)
      throw new IllegalArgumentException("Enable rei.chatgpt-plan.enabled for Windows local use");
    return value;
  }

  /** ChatGPTとの連携に関する出力先を処理する。 */
  private void out(String text) {
    output().println(text);
  }

  /** 要求された処理を実行する。 */
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
