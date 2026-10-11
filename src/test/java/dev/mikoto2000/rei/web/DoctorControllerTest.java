package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.doctor.*;
import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class DoctorControllerTest {
  @Test void passiveDiagnosisNeverSubmitsARunAndActiveDiagnosisHasOnlyNamedChecks() {
    var passive=mock(DoctorService.class);var active=mock(DoctorActiveService.class);var chat=mock(ChatSubmitService.class);
    when(passive.passive(true)).thenReturn(List.of());var controller=new DoctorController(passive,active,chat);
    assertNotNull(controller.passive(true).text());verify(passive).passive(true);verifyNoInteractions(chat,active);
    var run=new AgentRunContext("run","session",java.nio.file.Path.of("."),"project",AgentRunContext.RequestSource.WEB);
    when(chat.submit("/doctor --details --check connectivity --check codex","project","session",AgentRunContext.Mode.EXCLUSIVE,"receipt")).thenReturn(run);
    var response=controller.active(new DoctorController.ActiveRequest("project","session",List.of("connectivity","codex","connectivity"),true),"receipt");
    assertEquals("run",response.getBody().runId());
    verify(chat).submit("/doctor --details --check connectivity --check codex","project","session",AgentRunContext.Mode.EXCLUSIVE,"receipt");
    assertThrows(IllegalArgumentException.class,()->controller.active(new DoctorController.ActiveRequest("project","session",List.of("arbitrary-command"),false),"receipt"));
    assertThrows(IllegalArgumentException.class,()->controller.active(new DoctorController.ActiveRequest("project","session",List.of(),false),"receipt"));
    verifyNoInteractions(active);
  }
}
