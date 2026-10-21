package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionMetadata;
import dev.mikoto2000.rei.application.session.SessionQueryService;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.reflection.GoalReflectionRepository;
import dev.mikoto2000.rei.reflection.GoalReflectionService;
import dev.mikoto2000.rei.reflection.ReflectionLessonService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 振り返り受付窓口の結果と状態の契約を検証するテスト。 */
class ReflectionControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 振り返りについて、利用登録済みプロジェクトを読み取ることを確認する。 */
  @Test
  void reflectionReadsUseRegisteredProjectAndLessonsCaptureTheWebSession() {
    var projects = new ProjectRegistry(directory.resolve("projects.json"));
    var project = projects.resolve(directory);
    var repo = mock(GoalReflectionRepository.class);
    var collection = mock(GoalReflectionService.class);
    var sessions = mock(SessionQueryService.class);
    when(repo.list(project.id())).thenReturn(List.of());
    var controller = new ReflectionController(repo, collection, projects, sessions);
    assertEquals("[]", controller.list(project.id()).text());
    verify(repo).list(project.id());
    assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () -> controller.list("foreign"));
    var lessons = mock(ReflectionLessonService.class);
    controller.setLessons(lessons);
    when(sessions.getSession("session"))
        .thenReturn(
            new SessionMetadata("session", project.id(), "title", Instant.EPOCH, Instant.EPOCH));
    when(lessons.list(any())).thenReturn(List.of());
    assertEquals("[]", controller.lessons(project.id(), "session").text());
    var owner =
        org.mockito.ArgumentCaptor.forClass(dev.mikoto2000.rei.core.chat.AgentRunContext.class);
    verify(lessons).list(owner.capture());
    assertEquals(
        dev.mikoto2000.rei.core.chat.AgentRunContext.RequestSource.WEB,
        owner.getValue().requestSource());
    assertEquals("session", owner.getValue().conversationId());
    when(sessions.getSession("foreign"))
        .thenReturn(new SessionMetadata("foreign", "other", "title", Instant.EPOCH, Instant.EPOCH));
    assertThrows(IllegalArgumentException.class, () -> controller.lessons(project.id(), "foreign"));
    verifyNoMoreInteractions(lessons);
  }
}
