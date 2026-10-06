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
