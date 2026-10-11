package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.activity.ActivityCommand;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.application.run.IntegrationRunSubmitService;
class ActivityCommandsControllerTest {
  @TempDir Path directory;
  @Test void contextTemporarilySelectsOnlyTheRequestProjectAndRestoresShellScope()throws Exception {
    var registry=new ProjectRegistry(directory.resolve("projects.json"));var first=registry.resolve(Files.createDirectory(directory.resolve("first")));var second=registry.resolve(Files.createDirectory(directory.resolve("second")));
    var projects=new ProjectService(second.root(),registry);var command=mock(ActivityCommand.class);
    when(command.reportContext("today")).thenAnswer(call->{assertEquals(first.id(),projects.currentContext().id());return "context";});
    var api=new ActivityCommandsController(command,registry,projects,mock(IntegrationRunSubmitService.class));
    try(var scope=ProjectClientScope.open(projects.newClient())){assertEquals(second.id(),projects.currentContext().id());assertEquals("context",api.context(first.id(),"today").text());assertEquals(second.id(),projects.currentContext().id());}
  }
  @Test void coachingChangesRequireTheViewedRevision() {
    var command=mock(ActivityCommand.class);var registry=new ProjectRegistry(directory.resolve("projects.json"));var api=new ActivityCommandsController(command,registry,new ProjectService(directory,registry),mock(IntegrationRunSubmitService.class));
    assertThrows(IllegalArgumentException.class,()->api.enabled(new ActivityCommandsController.Enable(true,null)));verifyNoInteractions(command);
    when(command.reportCoachingEnabled(true,2)).thenReturn("enabled");assertEquals("enabled",api.enabled(new ActivityCommandsController.Enable(true,2L)).text());verify(command).reportCoachingEnabled(true,2);
  }
}
