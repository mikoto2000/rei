package dev.mikoto2000.rei.externalagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** 初学者レビューコマンドの結果と状態の契約を検証するテスト。 */
class BeginnerReviewCommandTest {
  /** 外部エージェントへの委譲で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @Test
  void parsesAudienceKnowledgePlatformOrderAndMode() {
    var r =
        BeginnerReviewRequest.parse(
            new String[] {
              "beginner",
              "--root",
              "training with spaces",
              "--entry",
              "intro.mdx",
              "--chapter",
              "intro.mdx",
              "--chapter",
              "next.md",
              "--audience",
              "new developers",
              "--prerequisite",
              "Shell",
              "--os",
              "Windows",
              "--shell",
              "PowerShell",
              "--mode",
              "llm"
            });
    assertEquals("training with spaces", r.root());
    assertEquals(java.util.List.of("intro.mdx", "next.md"), r.order());
    assertEquals(java.util.Set.of("Shell"), r.prerequisites());
    assertEquals("PowerShell", r.shell());
    assertThrows(
        IllegalArgumentException.class, () -> BeginnerReviewRequest.parse(new String[] {"expert"}));
  }

  /** 既存の会話実行待ちを利用することを確認する。 */
  @Test
  void thinShellAdapterUsesExistingConversationQueueAndPreservesQuoting() {
    var conversations = mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);
    assertEquals(
        0,
        new CommandLine(new BeginnerReviewCommand(conversations))
            .execute("beginner", "--root", "training with spaces", "--entry", "intro.md"));
    verify(conversations)
        .submit("/material-review beginner --root \"training with spaces\" --entry intro.md");
  }
}
