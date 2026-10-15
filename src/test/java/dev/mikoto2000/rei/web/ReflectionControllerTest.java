package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.reflection.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReflectionControllerTest {
  @TempDir Path directory;

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
