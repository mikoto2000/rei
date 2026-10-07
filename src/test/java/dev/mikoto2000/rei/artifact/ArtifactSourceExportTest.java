package dev.mikoto2000.rei.artifact;
import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.project.*;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class ArtifactSourceExportTest {
  @TempDir Path root;
  @Test void sessionExportCopiesOnlyTheOwnedSavedProposalWithoutApplyingIt() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var db=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var changes=new TextChangeSetService(new TextChangeSetRepository(db));
    Files.writeString(root.resolve("note.md"),"before");
    var proposal=changes.propose(project,new TextChangeSetService.Request("note.md","before","after"));
    var store=new ArtifactStore(db,projects,root.resolve("delivery"),Clock.systemUTC(),new ArtifactProperties());
    var item=store.publishSession(project,"session","proposal:"+proposal.id(),"text/plain","proposal.txt",changes.exportProposal(project,proposal.id()));
    assertThat(item.owner()).isEqualTo("SESSION");assertThat(item.runId()).isNull();
    assertThat(new String(store.content(project.id(),"session",item.artifactId()),java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("after");
    assertThat(Files.readString(root.resolve("note.md"))).isEqualTo("before");
    assertThat(changes.inspect(project,proposal.id()).status()).isEqualTo("PROPOSED");
    var other=projects.resolve(Files.createDirectory(root.resolve("other")));
    assertThatThrownBy(()->changes.exportProposal(other,proposal.id())).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void exportAdmissionRequiresAnExistingSessionOfTheSelectedProject() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var db=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var changes=new TextChangeSetService(new TextChangeSetRepository(db));
    Files.writeString(root.resolve("note.md"),"before");
    var proposal=changes.propose(project,new TextChangeSetService.Request("note.md","before","after"));
    var sessions=org.mockito.Mockito.mock(dev.mikoto2000.rei.application.session.SessionLifecycle.class);
    org.mockito.Mockito.when(sessions.validate("foreign",project.id())).thenThrow(new dev.mikoto2000.rei.application.run.SessionConflictException());
    var store=new ArtifactStore(db,projects,root.resolve("delivery"),Clock.systemUTC(),new ArtifactProperties());
    var exports=new ArtifactSourceExport(store,projects,sessions,changes,null);
    assertThatThrownBy(()->exports.export(project.id(),"foreign","CHANGE_SET_PROPOSAL",proposal.id(),null)).isInstanceOf(dev.mikoto2000.rei.application.run.ResourceNotFoundException.class);
    assertThat(store.list(project.id(),null,null,10,null).items()).isEmpty();
    var item=exports.export(project.id(),"owned","CHANGE_SET_PROPOSAL",proposal.id(),null);
    assertThat(item.owner()).isEqualTo("SESSION");
    assertThat(exports.export(project.id(),"owned","CHANGE_SET_PROPOSAL",proposal.id(),null).artifactId()).isEqualTo(item.artifactId());
    assertThatThrownBy(()->exports.export(project.id(),"owned","PATH","../../secrets",null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(Files.readString(root.resolve("note.md"))).isEqualTo("before");
  }
}
