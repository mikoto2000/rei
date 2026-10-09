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
import dev.mikoto2000.rei.core.policy.*;
class DependencyResumeRecheckTest {
 @TempDir Path root;
 @Test void completedFileAndPrerequisiteAreFreshlyCheckedWithoutRewritingHistory()throws Exception{
  var repo=new PersistentDependencyRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db")),Clock.systemUTC());
  var owner=new AgentRunContext("r","s",root,"p");
  var parent=repo.create(owner,new DependencySpec(DependencySpec.Kind.FILE_EXISTS,"parent",null),Duration.ofHours(1),List.of());
  var child=repo.create(owner,new DependencySpec(DependencySpec.Kind.FILE_EXISTS,"child",null),Duration.ofHours(1),List.of(parent.id()));
  var probe=new DependencySourceProbe(new dev.mikoto2000.rei.goal.FileGoalVerifier(),null,null,(id,u,e)->new DependencyObservation(id,DependencyState.BLOCKED,"unused"),Clock.systemUTC());
  var service=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(false),new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null)),e->{});
  Files.writeString(root.resolve("parent"),"ready");Files.writeString(root.resolve("child"),"ready");
  service.inspect("p",parent.id(),false);service.inspect("p",child.id(),false);
  assertEquals(DependencyState.COMPLETED,service.recheckForResume("p",child.id()).state());
  Files.delete(root.resolve("parent"));
  assertEquals(DependencyState.BLOCKED,service.recheckForResume("p",child.id()).state());
  assertEquals(DependencyState.COMPLETED,repo.get("p",child.id()).state());
 }
 @Test void automaticRecheckCannotUseTerminalHttpFactToBypassPermission(){
  var repo=new PersistentDependencyRepository(new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db")),Clock.systemUTC());
  var entry=repo.create(new AgentRunContext("r","s",root,"p"),new DependencySpec(DependencySpec.Kind.HTTP_STATUS,"https://example.com","200"),Duration.ofHours(1),List.of());
  repo.observe(entry,DependencyState.COMPLETED,"http_matches");var probe=mock(DependencyProbe.class);
  var service=new DependencyObservationService(repo,probe,new DependencyWatcherProperties(false),new ToolPermissionPolicy(new ToolPermissionProperties(true,null,null,null)),e->{});
  assertEquals("permission_required",service.recheckForResume("p",entry.id()).detail());verifyNoInteractions(probe);
 }
}