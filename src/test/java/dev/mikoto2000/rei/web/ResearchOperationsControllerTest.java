package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchOperationsControllerTest {
  @Test
  void namedResearchRoutesValidateExistingGrammarAndCaptureTheRequestedSession() {
    var chat = mock(ChatSubmitService.class);
    var run =
        new AgentRunContext(
            "run",
            "session",
            java.nio.file.Path.of("."),
            "project",
            AgentRunContext.RequestSource.WEB);
    when(chat.submit(
            anyString(),
            eq("project"),
            eq("session"),
            eq(AgentRunContext.Mode.EXCLUSIVE),
            eq("receipt")))
        .thenReturn(run);
    var controller = new ResearchOperationsController(chat);
    assertEquals(
        "run",
        controller
            .paper(
                new ResearchOperationsController.Arguments(
                    "project", "session", List.of("search", "graph theory", "--limit", "2")),
                "receipt")
            .getBody()
            .runId());
    var command = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(chat)
        .submit(
            command.capture(),
            eq("project"),
            eq("session"),
            eq(AgentRunContext.Mode.EXCLUSIVE),
            eq("receipt"));
    assertTrue(command.getValue().startsWith("/paper "));
    assertTrue(command.getValue().contains("graph theory"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.material(
                new ResearchOperationsController.Arguments(
                    "project", "session", List.of("beginner", "--execute", "arbitrary")),
                "receipt"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.agent(
                new ResearchOperationsController.Agent(
                    "project", "session", "arbitrary", "execute", "anything"),
                "receipt"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            controller.paper(
                new ResearchOperationsController.Arguments(
                    "project", "session", List.of("execute", "anything")),
                "receipt"));
    verifyNoMoreInteractions(chat);
  }
}
