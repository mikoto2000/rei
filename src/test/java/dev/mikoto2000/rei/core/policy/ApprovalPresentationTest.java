package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.io.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.web.ApprovalController;
import picocli.CommandLine;

class ApprovalPresentationTest {
  @Test void shellDecisionsUseSelectedProjectAndRequireAnId() {
    var repository=mock(ToolApprovalRepository.class);var projects=mock(ProjectService.class);
    String project=UUID.randomUUID().toString();
    when(projects.currentContext()).thenReturn(new ProjectContext(project,"project",Path.of(".")));
    var command=new CommandLine(new ApprovalCommand(repository,projects));
    command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0,command.execute("approve","request"));verify(repository).decide(project,"request",true);
    assertEquals(0,command.execute("deny","other"));verify(repository).decide(project,"other",false);
    assertEquals(2,command.execute("approve"));
    verify(repository,times(2)).decide(anyString(),anyString(),anyBoolean());
  }
  @Test void webDecisionRequiresExplicitBooleanAndDoesNotDispatch() {
    var repository=mock(ToolApprovalRepository.class);var controller=new ApprovalController(repository);
    assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.decide("p","id",new ApprovalController.Decision(null)));
    verifyNoInteractions(repository);
    controller.decide("p","id",new ApprovalController.Decision(true));verify(repository).decide("p","id",true);
  }
}
