package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.dependency.DependencyObservationService;
import dev.mikoto2000.rei.core.dependency.DependencySpec;
import dev.mikoto2000.rei.core.dependency.DependencyState;
import dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 依存条件受付窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DependencyControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** Web APIによる操作の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void namedCheckUsesTheWebPermissionBoundaryBeforeObservingAndCancelIsProjectOwned() {
    var projects =
        new dev.mikoto2000.rei.core.project.ProjectRegistry(dir.resolve("projects.json"));
    var project = projects.resolve(dir);
    var repo =
        new PersistentDependencyRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("dependencies.db")),
            Clock.systemUTC());
    var observations = mock(DependencyObservationService.class);
    var guard = mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class);
    var controller = new DependencyController(repo, observations, projects, guard);
    var entry =
        repo.create(
            new AgentRunContext("run", "session", dir, project.id()),
            new DependencySpec(DependencySpec.Kind.HTTP_STATUS, "http://127.0.0.1:9999", "200"),
            Duration.ofHours(1),
            List.of());
    when(observations.inspect(project.id(), entry.id(), true)).thenReturn(entry);
    assertEquals(entry, controller.check(project.id(), entry.id()));
    var order = inOrder(guard, observations);
    var owner = org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
    order.verify(guard).check(eq("checkHttpDependency"), anyString(), owner.capture());
    order.verify(observations).inspect(project.id(), entry.id(), true);
    assertEquals(AgentRunContext.RequestSource.WEB, owner.getValue().requestSource());
    assertThrows(IllegalArgumentException.class, () -> controller.cancel("foreign", entry.id()));
    assertEquals(DependencyState.CANCELLED, controller.cancel(project.id(), entry.id()).state());
  }

  /** 明示的な回答について、現在形式のバージョンを必要とすることを確認する。 */
  @Test
  void explicitAnswerRequiresCurrentVersionAndDoesNotCompleteOrExecuteDependency() {
    var repo =
        new PersistentDependencyRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("dependencies.db")),
            Clock.systemUTC());
    var events = mock(DependencyObservationService.class);
    var controller = new DependencyController(repo, events);
    var entry =
        repo.create(
            new AgentRunContext("run", "session", dir, "p"),
            new DependencySpec(DependencySpec.Kind.USER_ANSWER, "Which option?", null),
            Duration.ofHours(1),
            List.of());
    assertEquals(1, controller.list("p").size());
    assertThrows(IllegalArgumentException.class, () -> controller.show("other", entry.id()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.answer("p", entry.id(), new DependencyController.AnswerRequest(null, "A")));
    assertThrows(
        dev.mikoto2000.rei.application.state.OperationConflictException.class,
        () -> controller.answer("p", entry.id(), new DependencyController.AnswerRequest(99L, "A")));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(events);
    var answered =
        controller.answer(
            "p", entry.id(), new DependencyController.AnswerRequest(entry.version(), "Choice A"));
    assertEquals("Choice A", answered.answer());
    assertEquals(DependencyState.WAITING, answered.state());
    assertEquals(entry.version() + 1, answered.version());
    verify(events).flushFacts();
    verifyNoMoreInteractions(events);
    assertThrows(
        dev.mikoto2000.rei.application.state.OperationConflictException.class,
        () ->
            controller.answer(
                "p",
                entry.id(),
                new DependencyController.AnswerRequest(entry.version(), "Choice B")));
    assertEquals("Choice A", repo.get("p", entry.id()).answer());
  }
}
