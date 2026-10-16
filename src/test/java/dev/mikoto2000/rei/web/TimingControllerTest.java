package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.timing.TimingRecorder;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimingControllerTest {
  @TempDir Path directory;

  @Test
  void timingNeverReadsAnotherProjectsSessionAndRetainsMissingAndDisabledMessages() {
    var registry = new ProjectRegistry(directory.resolve("projects.json"));
    var project = registry.resolve(directory);
    var recorder = mock(TimingRecorder.class);
    var sessions = mock(SessionLifecycle.class);
    var controller = new TimingController(recorder, registry, sessions);
    assertEquals("時間計測は無効です", controller.show(null, null, "last", false).text());
    verifyNoInteractions(sessions);
    when(recorder.enabled()).thenReturn(true);
    assertEquals("現在のSessionの計測履歴はありません", controller.show(null, null, "last", false).text());
    when(recorder.latest(project.id(), "session")).thenReturn(Optional.empty());
    assertTrue(
        controller.show(project.id(), "session", "last", false).text().startsWith("計測履歴なし:"));
    verify(sessions).validate("session", project.id());
    doThrow(new IllegalArgumentException("foreign"))
        .when(sessions)
        .validate("foreign", project.id());
    assertThrows(
        IllegalArgumentException.class,
        () -> controller.show(project.id(), "foreign", "run", true));
    verify(recorder, never()).snapshot(anyString(), anyString(), anyString());
  }
}
