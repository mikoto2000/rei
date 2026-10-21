package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.chatgpt.ChatGptCommand;
import dev.mikoto2000.rei.chatgpt.PlanClient;
import dev.mikoto2000.rei.chatgpt.PlanOAuth;
import dev.mikoto2000.rei.chatgpt.PlanSseAssembler;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import picocli.CommandLine;

/** チャットGPTコマンド出力の結果と状態の契約を検証するテスト。 */
class ChatGptCommandOutputTest {
  /** 検証条件を再現するための作業ルート。 */
  @CommandLine.Command(name = "fixture")
  static class Root {}

  /** 終端書き込み先を保持することを確認する。 */
  @Test
  void sharedCommandSpecRebindingPreservesTerminalWriter() {
    @SuppressWarnings("unchecked")
    var provider = (ObjectProvider<PlanOAuth>) mock(ObjectProvider.class);
    var oauth = mock(PlanOAuth.class);
    when(provider.getIfAvailable()).thenReturn(oauth);
    when(oauth.status()).thenReturn(List.of("日本語の登録状態"));
    var bean = new ChatGptCommand(provider, null, null);
    var command = new CommandLine(new Root()).addSubcommand("chatgpt", bean);
    var output = new StringWriter();
    var terminal = mock(Terminal.class);
    when(terminal.writer()).thenReturn(new PrintWriter(output));
    ReiApplication.configureCommandOutput(command, terminal);
    new CommandLine(bean).setOut(new PrintWriter(new StringWriter()));
    assertEquals(0, command.execute("chatgpt", "status"));
    assertTrue(output.toString().contains("日本語の登録状態"));
  }

  /** れいの起動で、状態の変化と結果を、通知・表示の契約に従って伝えることを確認する。 */
  @Test
  void inferDisplaysCompletedStreamTextAndSafeStructureOnShellWriter() {
    String body =
        "data:"
            + " {\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hello,"
            + " world!\"}\n\n"
            + "data:"
            + " {\"type\":\"response.completed\",\"response\":{\"id\":\"private-response-id\",\"status\":\"completed\",\"output\":[],\"usage\":{\"total_tokens\":34}}}\n\n";
    var result =
        new PlanSseAssembler()
            .read(
                new ByteArrayInputStream(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                10000);
    var output = inferOutput(result);
    assertTrue(output.contains("COMPLETED; usage=34; error=null"));
    assertTrue(output.contains("Hello, world!"));
    assertTrue(output.contains("terminalItems=0"));
    assertTrue(output.contains("textLength=13"));
    assertTrue(output.contains("textSource=stream"));
    assertFalse(output.contains("private-response-id"));
    String diagnostic =
        output
            .lines()
            .filter(line -> line.startsWith("ChatGPT plan output:"))
            .findFirst()
            .orElseThrow();
    assertFalse(diagnostic.contains("Hello, world!"));
  }

  /** れいの起動で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void emptyCompletedOutputIsExplainedInsteadOfPrintingABlankLine() {
    assertTrue(
        inferOutput(
                new PlanSseAssembler.Result(
                    PlanSseAssembler.State.COMPLETED, "", 34, "private-id", null))
            .contains("completed without output text"));
  }

  /** {@code inferOutput} の条件を再現するための補助情報を返す。 */
  private String inferOutput(PlanSseAssembler.Result result) {
    @SuppressWarnings("unchecked")
    var provider = (ObjectProvider<PlanClient>) mock(ObjectProvider.class);
    var client = mock(PlanClient.class);
    when(provider.getIfAvailable()).thenReturn(client);
    when(client.infer(
            eq("personal"),
            eq("gpt-6.1-sol"),
            anyString(),
            eq("Say hello"),
            any(),
            anyInt(),
            anyInt(),
            any()))
        .thenReturn(result);
    var projects = mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    when(projects.currentClient())
        .thenReturn(mock(dev.mikoto2000.rei.core.project.ProjectClient.class));
    when(projects.currentContext())
        .thenReturn(
            new dev.mikoto2000.rei.core.project.ProjectContext(
                java.util.UUID.randomUUID().toString(), "test", java.nio.file.Path.of(".")));
    when(projects.currentSessionId()).thenReturn("test-session");
    var bean = new ChatGptCommand(null, provider, null);
    bean.configureProbe(projects, mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class));
    var command = new CommandLine(new Root()).addSubcommand("chatgpt", bean);
    var output = new StringWriter();
    bean.setShellOutput(new PrintWriter(output));
    new CommandLine(bean).setOut(new PrintWriter(new StringWriter()));
    assertEquals(
        0,
        command.execute(
            "chatgpt", "infer", "--account", "personal", "--model", "gpt-6.1-sol", "Say hello"));
    return output.toString();
  }
}
