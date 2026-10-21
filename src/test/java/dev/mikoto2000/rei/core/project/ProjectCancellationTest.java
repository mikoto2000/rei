package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.service.CommandCancellationService;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクトキャンセルの結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectCancellationTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** プロジェクトごとの作業環境の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void cancellingBDoesNotInterruptA() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var projects =
        connect(new ProjectService(a, new ProjectRegistry(temp.resolve("projects.json"))));
    var run = new AgentRunContext("run", projects.currentContext(), "chat:main");
    var cancellation = new CommandCancellationService();
    try (var scope = AgentRunScope.open(run)) {
      cancellation.begin(Thread.currentThread());
    }
    projects.cd(b.toString());
    assertThat(cancellation.cancelCurrentProject()).isFalse();
    assertThat(Thread.currentThread().isInterrupted()).isFalse();
    cancellation.clear();
  }
}
