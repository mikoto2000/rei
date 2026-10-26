package dev.mikoto2000.rei.core.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** エージェント実行対象範囲の結果と状態の契約を検証するテスト。 */
class AgentRunScopeTest {
  /** 会話の実行で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void bindingIsLexicalAndDoesNotLeakToShell() {
    var context = new AgentRunContext("run", "chat:main", Path.of("project-a"));
    assertThat(AgentRunScope.current()).isNull();
    assertThat(dev.mikoto2000.rei.context.RunIdentity.currentRunId()).isNull();
    try (var scope = AgentRunScope.open(context)) {
      assertThat(AgentRunScope.current()).isEqualTo(context);
      assertThat(dev.mikoto2000.rei.context.RunIdentity.currentRunId()).isEqualTo("run");
      try (var nested =
          AgentRunScope.open(new AgentRunContext("nested", "chat:main", Path.of(".")))) {
        assertThat(dev.mikoto2000.rei.context.RunIdentity.currentRunId()).isEqualTo("nested");
      }
      assertThat(dev.mikoto2000.rei.context.RunIdentity.currentRunId()).isEqualTo("run");
      try (var unbound = AgentRunScope.open(null)) {
        assertThat(dev.mikoto2000.rei.context.RunIdentity.currentRunId()).isNull();
      }
      assertThat(dev.mikoto2000.rei.context.RunIdentity.currentRunId()).isEqualTo("run");
    }
    assertThat(AgentRunScope.current()).isNull();
    assertThat(dev.mikoto2000.rei.context.RunIdentity.currentRunId()).isNull();
  }
}
