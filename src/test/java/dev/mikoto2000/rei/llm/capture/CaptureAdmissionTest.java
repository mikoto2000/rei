package dev.mikoto2000.rei.llm.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.session.ShellConversationService;
import dev.mikoto2000.rei.core.chat.ConversationInputRouter;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.project.ProjectService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 取得記録受付の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class CaptureAdmissionTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** モデル応答の記録の中断時に、実行状態と後続の処理が中断の契約に従うことを確認する。 */
  @Test
  void queuedCancellationEndsCaptureAndAnewReservationCanBeCreated() {
    var tasks = new ArrayList<Runnable>();
    var router = new ConversationInputRouter(tasks::add, (c, p, q) -> {});
    var store = new CaptureStore();
    router.setRequestCaptures(store);
    var projects = new ProjectService(dir, new ProjectRegistry(dir.resolve("projects.json")));
    var shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(
                new dev.mikoto2000.rei.conversation.FileSessionRepository(
                    dir.resolve("sessions.json")),
                java.time.Clock.systemUTC()),
            router::submit);
    shell.setCaptures(store);
    try (var scope = projects.newClient().open()) {
      shell.submit("first");
      store.reserve(projects.currentClient(), shell.currentSessionId());
      var captured = shell.submit("second");
      assertThat(store.sessions().getFirst().runId()).isEqualTo(captured.runId());
      assertThat(router.cancelQueued(captured.runId())).isTrue();
      assertThat(store.sessions().getFirst().outcome()).isEqualTo("CANCELLED");
      assertThat(store.sessions().getFirst().ended()).isNotNull();
      store.reserve(projects.currentClient(), shell.currentSessionId());
    }
  }

  /** モデル応答の記録で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void deferredExecutorRejectionEndsTheAcceptedCapture() {
    var tasks = new ArrayList<Runnable>();
    var dispatches = new java.util.concurrent.atomic.AtomicInteger();
    var router =
        new ConversationInputRouter(
            task -> {
              if (dispatches.incrementAndGet() == 1) tasks.add(task);
              else throw new RejectedExecutionException("test");
            },
            (c, p, q) -> {});
    var store = new CaptureStore();
    router.setRequestCaptures(store);
    var projects = new ProjectService(dir, new ProjectRegistry(dir.resolve("projects.json")));
    var shell =
        new ShellConversationService(
            projects,
            new SessionLifecycle(
                new dev.mikoto2000.rei.conversation.FileSessionRepository(
                    dir.resolve("sessions.json")),
                java.time.Clock.systemUTC()),
            router::submit);
    shell.setCaptures(store);
    try (var scope = projects.newClient().open()) {
      shell.submit("first");
      store.reserve(projects.currentClient(), shell.currentSessionId());
      shell.submit("second");
      assertThatThrownBy(() -> tasks.removeFirst().run())
          .isInstanceOf(RejectedExecutionException.class);
      assertThat(store.sessions().getFirst().outcome()).isEqualTo("START_FAILED");
      assertThat(store.sessions().getFirst().ended()).isNotNull();
    }
  }
}
