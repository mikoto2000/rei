package dev.mikoto2000.rei.core.dependency;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.policy.*;

@Tag("integration")
class DependencyToolsTest {
  @TempDir Path dir;final String project=UUID.randomUUID().toString();
  PersistentDependencyRepository repo;DependencyObservationService service;DependencySourceProbe source;DependencyTools tools;
  @BeforeEach void setup(){repo=new PersistentDependencyRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("deps.db")),Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));source=mock(DependencySourceProbe.class);
    when(source.probe(any())).thenAnswer(invocation->{var entry=invocation.getArgument(0,PersistentDependencyRepository.Entry.class);return new DependencyObservation(entry.id(),DependencyState.WAITING,"user_answer_waiting");});
    service=new DependencyObservationService(repo,source,new DependencyWatcherProperties(false),new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null)),e->{});tools=new DependencyTools(repo,service,source);}
  AgentRunContext owner(){return new AgentRunContext("r","s",dir,project);}
  @Test void registrationIsScopedAndReadInspectionCannotReachNetwork() {
    assertThrows(IllegalArgumentException.class,()->tools.registerDependency("FILE_EXISTS","file",null,null,null));
    String id;try(var scope=AgentRunScope.open(owner())) {
      id=tools.registerDependency("HTTP_STATUS","https://example.com","200","PT1H",null).id();
      assertThrows(IllegalArgumentException.class,()->tools.checkDependency(id));verifyNoInteractions(source);
      assertEquals(DependencyState.WAITING,tools.checkHttpDependency(id).state());
    }
    try(var scope=AgentRunScope.open(new AgentRunContext("r","other",dir,project))){assertThrows(IllegalArgumentException.class,()->tools.dependencyStatus(id));}
  }
  @Test void callbackHidesContextAndWaitingPreservesStagnationState() throws Exception {
    var execution=new dev.mikoto2000.rei.core.stagnation.RunExecutionContext("r",new dev.mikoto2000.rei.llm.OutputLimitRunBudget(1,10),new dev.mikoto2000.rei.core.stagnation.ProgressEvaluator(dir),new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC()),e->{});
    var callbacks=org.springframework.ai.tool.method.MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
    var callback=Arrays.stream(callbacks).filter(c->c.getToolDefinition().name().equals("waitForDependency")).findFirst().orElseThrow();
    assertFalse(callback.getToolDefinition().inputSchema().contains("toolContext"));
    try(var scope=AgentRunScope.open(owner())) {
      String id=tools.registerDependency("USER_ANSWER","Proceed?",null,null,null).id();execution.beginIteration();
      var before=execution.evaluator().beforeTool("waitForDependency","{}");
      var result=callback.call("{\"dependencyId\":\""+id+"\",\"timeoutSeconds\":0}",new org.springframework.ai.chat.model.ToolContext(Map.of(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY,execution)));
      assertTrue(result.contains("WAITING"));execution.recordTool("waitForDependency","{}",result,before);execution.endIteration();assertEquals(0,execution.detector().stagnationCount());
      tools.cancelDependency(id);assertEquals(DependencyState.CANCELLED,tools.dependencyStatus(id).state());
    }
    assertFalse(Arrays.stream(callbacks).anyMatch(c->c.getToolDefinition().name().toLowerCase(Locale.ROOT).contains("answer")));
  }
  @Test void shellHumanAnswerCompletesOnlyItsOwnedProject() {
    PersistentDependencyRepository.Entry entry;try(var scope=AgentRunScope.open(owner())) {entry=tools.registerDependency("USER_ANSWER","Proceed?",null,null,null);}
    doAnswer(invocation->{var e=invocation.getArgument(0,PersistentDependencyRepository.Entry.class);return new DependencyObservation(e.id(),e.answer()==null?DependencyState.WAITING:DependencyState.COMPLETED,e.answer()==null?"user_answer_waiting":"user_answer_received");}).when(source).probe(any());
    var projects=mock(dev.mikoto2000.rei.core.project.ProjectService.class);when(projects.currentContext()).thenReturn(new dev.mikoto2000.rei.core.project.ProjectContext(project,"p",dir));
    var command=new DependencyCommand(repo,service,projects,mock(ToolPermissionGuard.class));command.setShellOutput(new java.io.PrintWriter(new java.io.StringWriter()));
    assertEquals(0,new picocli.CommandLine(command).execute("answer",entry.id(),"--value","Yes"));assertEquals(DependencyState.COMPLETED,repo.get(project,entry.id()).state());
    when(projects.currentContext()).thenReturn(new dev.mikoto2000.rei.core.project.ProjectContext(UUID.randomUUID().toString(),"other",dir));
    assertEquals(2,new picocli.CommandLine(command).execute("show",entry.id()));
  }
}
