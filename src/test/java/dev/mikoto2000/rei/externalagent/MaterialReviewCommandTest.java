package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 教材レビューコマンドの結果と状態の契約を検証するテスト。 */
class MaterialReviewCommandTest {
  /** 外部エージェントへの委譲で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void parsesOptionalTargetAndPreservesGenericReview() {
    for (String target : new String[] {null, ".", "docs", "training with spaces"}) {
      var command =
          ExternalAgentCommandRequest.parse(
              "/agent codex material-review" + (target == null ? "" : " " + target));
      assertEquals("material-review", command.action());
      assertEquals(target, command.target());
      assertTrue(
          ExternalAgentAuthorization.explicitRequest(
              "/agent codex material-review", ExternalAgentRequest.Agent.CODEX));
    }
    assertEquals("review", ExternalAgentCommandRequest.parse("/agent codex review .").action());
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalAgentCommandRequest.parse("/agent codex unknown ."));
    assertThrows(
        IllegalArgumentException.class,
        () -> ExternalAgentCommandRequest.parse("/agent claude material-review ."));
  }

  /** 外部エージェントへの委譲で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void shellQueuesMaterialReview() {
    var shell = mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);
    assertEquals(
        0,
        new CommandLine(new ExternalAgentCommand(shell))
            .execute("codex", "material-review", "docs"));
    verify(shell).submit("/agent codex material-review docs");
  }
}
