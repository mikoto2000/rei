package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.core.chat.AgentRunContext;

@Tag("integration")
class DependencyControllerTest {
  @TempDir Path dir;
  @Test void namedCheckUsesTheWebPermissionBoundaryBeforeObservingAndCancelIsProjectOwned() {
    var projects=new dev.mikoto2000.rei.core.project.ProjectRegistry(dir.resolve("projects.json"));var project=projects.resolve(dir);
    var repo=new PersistentDependencyRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("dependencies.db")),Clock.systemUTC());
    var observations=mock(DependencyObservationService.class);var guard=mock(dev.mikoto2000.rei.core.policy.ToolPermissionGuard.class);
    var controller=new DependencyController(repo,observations,projects,guard);
    var entry=repo.create(new AgentRunContext("run","session",dir,project.id()),new DependencySpec(DependencySpec.Kind.HTTP_STATUS,"http://127.0.0.1:9999","200"),Duration.ofHours(1),List.of());
    when(observations.inspect(project.id(),entry.id(),true)).thenReturn(entry);
    assertEquals(entry,controller.check(project.id(),entry.id()));
    var order=inOrder(guard,observations);var owner=org.mockito.ArgumentCaptor.forClass(AgentRunContext.class);
    order.verify(guard).check(eq("checkHttpDependency"),anyString(),owner.capture());order.verify(observations).inspect(project.id(),entry.id(),true);
    assertEquals(AgentRunContext.RequestSource.WEB,owner.getValue().requestSource());
    assertThrows(IllegalArgumentException.class,()->controller.cancel("foreign",entry.id()));
    assertEquals(DependencyState.CANCELLED,controller.cancel(project.id(),entry.id()).state());
  }
  @Test void explicitAnswerRequiresCurrentVersionAndDoesNotCompleteOrExecuteDependency() {
    var repo=new PersistentDependencyRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("dependencies.db")),Clock.systemUTC());
    var events=mock(DependencyObservationService.class);
    var controller=new DependencyController(repo,events);
    var entry=repo.create(new AgentRunContext("run","session",dir,"p"),new DependencySpec(DependencySpec.Kind.USER_ANSWER,"Which option?",null),Duration.ofHours(1),List.of());
    assertEquals(1,controller.list("p").size());
    assertThrows(IllegalArgumentException.class,()->controller.show("other",entry.id()));
    assertThrows(IllegalArgumentException.class,()->controller.answer("p",entry.id(),new DependencyController.AnswerRequest(null,"A")));
    assertThrows(dev.mikoto2000.rei.application.state.OperationConflictException.class,()->controller.answer("p",entry.id(),new DependencyController.AnswerRequest(99L,"A")));
    verifyNoInteractions(events);
    var answered=controller.answer("p",entry.id(),new DependencyController.AnswerRequest(entry.version(),"Choice A"));
    assertEquals("Choice A",answered.answer());
    assertEquals(DependencyState.WAITING,answered.state());
    assertEquals(entry.version()+1,answered.version());
    verify(events).flushFacts(); verifyNoMoreInteractions(events);
    assertThrows(dev.mikoto2000.rei.application.state.OperationConflictException.class,()->controller.answer("p",entry.id(),new DependencyController.AnswerRequest(entry.version(),"Choice B")));
    assertEquals("Choice A",repo.get("p",entry.id()).answer());
  }
}
