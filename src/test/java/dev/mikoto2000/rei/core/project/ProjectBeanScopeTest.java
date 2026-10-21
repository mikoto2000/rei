package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.core.working.WorkingSet;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクト機能の構成要素対象範囲の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectBeanScopeTest extends dev.mikoto2000.rei.core.project.ProjectClientTestSupport {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** プロジェクトごとの作業環境で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void restrictedRunsHaveSeparateTransientStateAndReleaseItAtTermination() {
    var scope = new ProjectBeanScope(() -> "same-project");
    var original = scope.get("state", Object::new);
    var reader =
        new AgentRunContext(
            "reader",
            "session",
            temp,
            "A",
            AgentRunContext.RequestSource.WEB,
            AgentRunContext.Mode.READ_ONLY);
    Object transientState;
    try (var binding = AgentRunScope.open(reader)) {
      transientState = scope.get("state", Object::new);
      assertThat(transientState).isNotSameAs(original);
      assertThat(scope.get("state", Object::new)).isSameAs(transientState);
      scope.releaseRun(reader.runId());
      assertThat(scope.get("state", Object::new)).isNotSameAs(transientState);
    }
    assertThat(scope.get("state", Object::new)).isSameAs(original);
  }

  /** プロジェクトごとの作業環境で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void workingSetIsScopedAndPersistsIndependentlyOfEvents() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var service =
        connect(new ProjectService(a, new ProjectRegistry(temp.resolve("projects.json"))));
    var scope = new ProjectBeanScope();
    var first = (WorkingSet) scope.get("workingSet", WorkingSet::new);
    first.enablePersistence(temp.resolve("state/files.json"));
    first.recordRead(a.resolve("A.java"));
    var run = new AgentRunContext("run", service.currentContext(), "chat:main");
    service.cd(b.toString());
    assertThat(((WorkingSet) scope.get("workingSet", WorkingSet::new)).getFiles()).isEmpty();
    try (var binding = AgentRunScope.open(run)) {
      assertThat(scope.get("workingSet", WorkingSet::new)).isSameAs(first);
    }
    service.cd(a.toString());
    assertThat(scope.get("workingSet", WorkingSet::new)).isSameAs(first);
    var reloaded = new WorkingSet();
    reloaded.enablePersistence(temp.resolve("state/files.json"));
    assertThat(reloaded.getFiles()).isEqualTo(first.getFiles());
  }
}
