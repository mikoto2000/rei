package dev.mikoto2000.rei.core.dependency;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.process.*;
import dev.mikoto2000.rei.goal.FileGoalVerifier;
import dev.mikoto2000.rei.workcontext.*;

@Tag("integration")
class DependencySourceProbeTest {
  @TempDir Path dir;
  final Clock clock=Clock.fixed(Instant.EPOCH,ZoneOffset.UTC);
  PersistentDependencyRepository repo(){return new PersistentDependencyRepository(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("deps.db")),clock);}
  PersistentDependencyRepository.Entry create(DependencySpec spec){return repo().create(new AgentRunContext("r","s",dir,"p"),spec,Duration.ofHours(1),List.of());}
  @Test void fileAnswerAndGitFactsUseTheirActualSources() throws Exception {
    var git=mock(WorkContextGit.class);var processes=mock(BackgroundProcessManager.class);var http=mock(DependencyHttpProbe.class);
    var probe=new DependencySourceProbe(new FileGoalVerifier(),git,processes,http,clock);
    var file=create(new DependencySpec(DependencySpec.Kind.FILE_EXISTS,"result",null));
    assertEquals(DependencyState.WAITING,probe.probe(file).state());Files.writeString(dir.resolve("result"),"ready");assertEquals(DependencyState.COMPLETED,probe.probe(file).state());
    var question=create(new DependencySpec(DependencySpec.Kind.USER_ANSWER,"Proceed?",null));assertEquals(DependencyState.WAITING,probe.probe(question).state());
    var answered=repo().answer("p",question.id(),"Yes");assertEquals(DependencyState.COMPLETED,probe.probe(answered).state());
    when(git.capture(eq(dir),any())).thenReturn(new WorkContext.GitState(dir.toString(),"main","a".repeat(40),Instant.EPOCH));
    var state=create(new DependencySpec(DependencySpec.Kind.GIT_STATE_CHANGED,"HEAD",probe.gitBaseline(dir)));
    assertEquals(DependencyState.WAITING,probe.probe(state).state());
    when(git.capture(eq(dir),any())).thenReturn(new WorkContext.GitState(dir.toString(),"other","a".repeat(40),Instant.EPOCH));
    assertEquals(DependencyState.COMPLETED,probe.probe(state).state());
    when(git.capture(eq(dir),any())).thenReturn(new WorkContext.GitState(dir.toString(),null,null,Instant.EPOCH));
    assertEquals(DependencyState.BLOCKED,probe.probe(state).state());verifyNoInteractions(processes,http);
  }
  @Test void processExitRequiresOwnedActualStatusAndPreservesSixStates() {
    var processes=mock(BackgroundProcessManager.class);var probe=new DependencySourceProbe(new FileGoalVerifier(),mock(WorkContextGit.class),processes,mock(DependencyHttpProbe.class),clock);
    var entry=create(new DependencySpec(DependencySpec.Kind.PROCESS_EXIT,"process","0"));
    for(var state:List.of(BackgroundProcessStatus.RUNNING,BackgroundProcessStatus.EXITED,BackgroundProcessStatus.FAILED,BackgroundProcessStatus.KILLED)) {
      when(processes.statusOwned("process","p","s",dir)).thenReturn(new BackgroundProcessSnapshot("process",1,state,state==BackgroundProcessStatus.EXITED?0:null,Instant.EPOCH,null,0,List.of(),List.of(),true,""));
      var expected=switch(state){case RUNNING->DependencyState.RUNNING;case EXITED->DependencyState.COMPLETED;case FAILED->DependencyState.FAILED;case KILLED->DependencyState.CANCELLED;default->throw new AssertionError();};
      assertEquals(expected,probe.probe(entry).state());
    }
    when(processes.statusOwned("process","p","s",dir)).thenReturn(new BackgroundProcessSnapshot("process",-1,BackgroundProcessStatus.FAILED,null,null,null,0,List.of(),List.of(),false,""));
    assertEquals(DependencyState.BLOCKED,probe.probe(entry).state());
  }
  @Test void realGitBranchTransitionIsObservedWithoutCommandsFromTheModel() throws Exception {
    gitCommand("init");Files.writeString(dir.resolve("tracked"),"value");gitCommand("add","tracked");
    gitCommand("-c","user.name=Test","-c","user.email=test@example.com","-c","commit.gpgSign=false","-c","core.hooksPath="+dir.resolve("no-hooks"),"commit","-m","initial");
    var probe=new DependencySourceProbe(new FileGoalVerifier(),new WorkContextGit(),mock(BackgroundProcessManager.class),mock(DependencyHttpProbe.class),clock);
    var entry=create(new DependencySpec(DependencySpec.Kind.GIT_STATE_CHANGED,"HEAD",probe.gitBaseline(dir)));
    assertEquals(DependencyState.WAITING,probe.probe(entry).state());gitCommand("switch","-c","watch-test");
    assertEquals(DependencyState.COMPLETED,probe.probe(entry).state());
  }
  void gitCommand(String... args) throws Exception {
    var command=new ArrayList<String>(List.of("git","-C",dir.toString()));command.addAll(List.of(args));
    var process=new ProcessBuilder(command).redirectErrorStream(true).start();
    assertTrue(process.waitFor(10,java.util.concurrent.TimeUnit.SECONDS));assertEquals(0,process.exitValue(),new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
  }
  @Test void fileChangeIncludesContentCreationAndDeletionWithoutExposingContents() throws Exception {
    var probe=new DependencySourceProbe(new FileGoalVerifier(),mock(WorkContextGit.class),mock(BackgroundProcessManager.class),mock(DependencyHttpProbe.class),clock);
    var absent=create(new DependencySpec(DependencySpec.Kind.FILE_CHANGED,"changing",probe.fileBaseline(dir,"changing")));
    assertEquals(DependencyState.WAITING,probe.probe(absent).state());Files.writeString(dir.resolve("changing"),"first");
    assertEquals(DependencyState.COMPLETED,probe.probe(absent).state());
    var existing=create(new DependencySpec(DependencySpec.Kind.FILE_CHANGED,"changing",probe.fileBaseline(dir,"changing")));
    assertEquals(DependencyState.WAITING,probe.probe(existing).state());Files.writeString(dir.resolve("changing"),"second");
    assertEquals(DependencyState.COMPLETED,probe.probe(existing).state());Files.delete(dir.resolve("changing"));
    assertEquals(DependencyState.COMPLETED,probe.probe(existing).state());
  }
}
