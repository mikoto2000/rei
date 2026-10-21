package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.doctor.DoctorActiveService;
import dev.mikoto2000.rei.doctor.DoctorService;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 環境診断受付窓口の結果と状態の契約を検証するテスト。 */
class DoctorControllerTest {
  /** Web APIによる操作で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void passiveDiagnosisNeverSubmitsARunAndActiveDiagnosisHasOnlyNamedChecks() {
    var passive = mock(DoctorService.class);
    var active = mock(DoctorActiveService.class);
    var chat = mock(ChatSubmitService.class);
    when(passive.passive(true)).thenReturn(List.of());
    var controller = new DoctorController(passive, active, chat);
    assertNotNull(controller.passive(true).text());
    verify(passive).passive(true);
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(chat, active);
    var run =
        new AgentRunContext(
            "run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            AgentRunContext.RequestSource.WEB);
    when(chat.submit(
            "/doctor --details --check connectivity --check codex",
            "project",
            "session",
            AgentRunContext.Mode.EXCLUSIVE,
            "receipt"))
        .thenReturn(run);
    var response =
        controller.active(
            new DoctorController.ActiveRequest(
                "project", "session", List.of("connectivity", "codex", "connectivity"), true),
            "receipt");
    assertEquals("run", response.getBody().runId());
    verify(chat)
        .submit(
            "/doctor --details --check connectivity --check codex",
            "project",
            "session",
            AgentRunContext.Mode.EXCLUSIVE,
            "receipt");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.active(
                new DoctorController.ActiveRequest(
                    "project", "session", List.of("arbitrary-command"), false),
                "receipt"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.active(
                new DoctorController.ActiveRequest("project", "session", List.of(), false),
                "receipt"));
    verifyNoInteractions(active);
  }
}
