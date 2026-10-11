package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.memory.command.MemoryCommandSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class ScopedMemoryControllerTest {
  @TempDir Path directory;
  @Test void namedReadRoutesValidateTheProjectAndPreserveShellDefaults() {
    var registry=new ProjectRegistry(directory.resolve("projects.json"));var project=registry.resolve(directory);
    var support=mock(MemoryCommandSupport.class);var controller=new ScopedMemoryController(registry,support);
    when(support.listForProject(project.id(),20,0)).thenReturn("No memories.");
    assertEquals("No memories.",controller.list(project.id(),20,0).text());
    controller.search(project.id(),"fixture",10);verify(support).searchForProject(project.id(),"fixture",10);
    controller.forget(project.id(),"memory");verify(support).forgetForProject(project.id(),"memory");
    assertThrows(dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,()->controller.list("unknown",20,0));
    controller.list(project.id(),0,-1);verify(support).listForProject(project.id(),0,-1);
    controller.search(project.id()," ",10);verify(support).searchForProject(project.id()," ",10);
  }
}
