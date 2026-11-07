package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** プロジェクトクライアント作業の分離の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectClientIsolationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temp;

  /** プロジェクトごとの作業環境で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void clientsRetainIndependentSelectionsAcrossReusedWorkerRequests() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    Files.createDirectory(a.resolve("child"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var first = projects.newClient();
    var second = projects.newClient();
    try (var worker = Executors.newSingleThreadExecutor()) {
      worker
          .submit(
              () -> {
                var scope = first.open();
                try (scope) {
                  projects.cd(a.toString());
                }
              })
          .get();
      worker
          .submit(
              () -> {
                var scope = second.open();
                try (scope) {
                  projects.cd(b.toString());
                }
              })
          .get();
      worker
          .submit(
              () -> {
                var scope = first.open();
                try (scope) {
                  assertThat(projects.currentProject()).isEqualTo(a);
                  assertThat(ProjectService.contextForOperation().root()).isEqualTo(a);
                  assertThat(projects.cd("child")).isEqualTo(a.resolve("child"));
                }
                assertThat(ProjectService.contextForOperation()).isNull();
                assertThatThrownBy(() -> projects.cd(a.toString()))
                    .isInstanceOf(IllegalStateException.class);
              })
          .get();
      worker
          .submit(
              () -> {
                var scope = second.open();
                try (scope) {
                  assertThat(projects.currentProject()).isEqualTo(b);
                  assertThat(ProjectService.currentProjectOrStartupDirectory()).isEqualTo(b);
                }
              })
          .get();
    }
  }

  /** プロジェクトごとの作業環境で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void parallelClientsResolveStateIndependentlyAndRunOwnershipTakesPrecedence() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var first = projects.newClient();
    var second = projects.newClient();
    var ready = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var one =
          workers.submit(
              () -> {
                var scope = first.open();
                try (scope) {
                  projects.cd(a.toString());
                  var owner = projects.currentContext();
                  ready.await(5, TimeUnit.SECONDS);
                  assertThat(ProjectService.contextForOperation()).isEqualTo(owner);
                  assertThat(projects.currentProject()).isEqualTo(a);
                  projects.cd(b.toString());
                  var run =
                      AgentRunScope.open(new AgentRunContext("run", owner, "chat:main"));
                  try (run) {
                    assertThat(ProjectService.contextForOperation()).isEqualTo(owner);
                    assertThat(projects.currentProject()).isEqualTo(b);
                    assertThat(ProjectService.currentProjectOrStartupDirectory()).isEqualTo(b);
                  }
                  assertThat(projects.currentProject()).isEqualTo(b);
                }
                return null;
              });
      var two =
          workers.submit(
              () -> {
                var scope = second.open();
                try (scope) {
                  projects.cd(b.toString());
                  ready.await(5, TimeUnit.SECONDS);
                  assertThat(ProjectService.contextForOperation().root()).isEqualTo(b);
                }
                return null;
              });
      one.get(10, TimeUnit.SECONDS);
      two.get(10, TimeUnit.SECONDS);
    }
  }

  /** プロジェクトごとの作業環境で、保存した情報を、再起動や復元後の処理へ正しく引き継ぐことを確認する。 */
  @Test
  void nestedScopesAndFailuresRestoreCallerWithoutChangingOtherClients() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var first = projects.newClient();
    var second = projects.newClient();
    var outer = first.open();
    try (outer) {
      projects.cd(a.toString());
      assertThatThrownBy(
              () -> {
                var inner = second.open();
                try (inner) {
                  projects.cd("missing");
                }
              })
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(projects.currentProject()).isEqualTo(a);
      var unrelated = new ProjectService(temp, new ProjectRegistry(temp.resolve("other.json")));
      assertThat(ProjectService.contextForOperation().root()).isEqualTo(a);
      assertThatThrownBy(() -> unrelated.cd(temp.toString()))
          .isInstanceOf(IllegalStateException.class);
      var inner = second.open();
      try (inner) {
        assertThat(projects.currentProject()).isEqualTo(temp);
        projects.remove(a.toString());
      }
      assertThat(projects.currentProject()).isEqualTo(a);
    }
    assertThat(ProjectService.contextForOperation()).isNull();
  }
}
