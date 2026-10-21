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

/** 時間計測受付窓口の結果と状態の契約を検証するテスト。 */
class TimingControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void timingNeverReadsAnotherProjectsSessionAndRetainsMissingAndDisabledMessages() {
    var registry = new ProjectRegistry(directory.resolve("projects.json"));
    var project = registry.resolve(directory);
    var recorder = mock(TimingRecorder.class);
    var sessions = mock(SessionLifecycle.class);
    var controller = new TimingController(recorder, registry, sessions);
    assertEquals("時間計測は無効です", controller.show(null, null, "last", false).text());
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
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
