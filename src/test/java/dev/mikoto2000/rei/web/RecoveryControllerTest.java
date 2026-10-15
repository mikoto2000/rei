package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import org.junit.jupiter.api.Test;

class RecoveryControllerTest {
  @Test
  void recoveryValidatesNamedActionsAndHashesBeforeDurableAdmission() {
    var chat = mock(ChatSubmitService.class);
    var run =
        new AgentRunContext(
            "run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            AgentRunContext.RequestSource.WEB);
    String id = "12345678-1234-1234-1234-123456789abc", hash = "a".repeat(64);
    when(chat.submit(
            "/document rollback " + id + " " + hash,
            "project",
            "session",
            AgentRunContext.Mode.EXCLUSIVE,
            "receipt"))
        .thenReturn(run);
    var controller = new RecoveryController(chat);
    var request = new RecoveryController.Request("project", "session", "rollback", id, hash);
    assertEquals("run", controller.document(request, "receipt").getBody().runId());
    verify(chat)
        .submit(
            "/document rollback " + id + " " + hash,
            "project",
            "session",
            AgentRunContext.Mode.EXCLUSIVE,
            "receipt");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.document(
                new RecoveryController.Request("project", "session", "execute", id, hash),
                "receipt"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.document(
                new RecoveryController.Request("project", "session", "show", id, hash), "receipt"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.repair(
                new RecoveryController.Request("project", "session", "apply", id, null),
                "receipt"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.repair(
                new RecoveryController.Request("project", "session", "apply", id, hash),
                "invalid key"));
    verifyNoMoreInteractions(chat);
  }
}
