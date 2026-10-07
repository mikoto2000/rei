package dev.mikoto2000.rei.artifact;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.task.*;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class TaskArtifactProjectionTest {
  @TempDir Path root;
  @Test void runResultsReferenceOnlyItsOwnedArtifactsWithoutReadingArbitraryFiles() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("state.db"));
    var store=new ArtifactStore(source,projects,root.resolve("delivery"),Clock.systemUTC(),new ArtifactProperties());
    var runs=new RunRegistry(Clock.systemUTC());var owner=new AgentRunContext("run","session",root,project.id());runs.register(owner);
    var saved=store.publish(owner,"output","text/plain","a.txt",new byte[0]);
    store.publish(new AgentRunContext("other","session",root,project.id()),"other","text/plain","b.txt",new byte[0]);
    var tasks=new TaskManagerService(projects,runs,null,null,null,null);tasks.setArtifactStore(store);
    assertThat(tasks.get(project.id(),"session","run:run").results()).filteredOn(r->r.kind().equals("ARTIFACT")).extracting(TaskView.Reference::id).containsExactly(saved.artifactId());
  }
  @Test void childArtifactsBelongToTheHumanSessionAndRemainDiscoverableFromTheChildTask() {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+root.resolve("state.db"));
    var runs=new RunRegistry(Clock.systemUTC());
    var parent=new AgentRunContext("parent","human-session",root,project.id());runs.register(parent);
    var child=new AgentRunContext("child","private-child-session",root,project.id());runs.registerChild(child,parent,"fixture");
    var store=new ArtifactStore(source,projects,root.resolve("delivery"),Clock.systemUTC(),new ArtifactProperties());store.setRunRegistry(runs);
    var artifact=store.publish(child,"output","text/plain","child.txt",new byte[0]);
    assertThat(artifact.sessionId()).isEqualTo("human-session");
    var tasks=new TaskManagerService(projects,runs,null,null,null,null);tasks.setArtifactStore(store);
    assertThat(tasks.get(project.id(),"human-session","run:child").results()).filteredOn(r->r.kind().equals("ARTIFACT")).extracting(TaskView.Reference::id).containsExactly(artifact.artifactId());
  }
}
