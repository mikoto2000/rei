package dev.mikoto2000.rei.github;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.core.dependency.*;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.attention.AttentionRepository;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class GitHubDependencyIntegrationTest {
  @TempDir Path root;
  @Test void onlyAnAuthenticatedMergedFactForTheOwnedProjectSessionAndPrCompletesTheDependency() throws Exception {
    var clock=GitHubWebhookServiceTest.CLOCK;
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var sessions=new SessionLifecycle(new GitHubWebhookServiceTest.Sessions(),clock);var session=sessions.create(project,"Merge").sessionId();
    var source=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var repo=new PersistentDependencyRepository(source,clock);var owner=new AgentRunContext("run",session,root,project.id());
    var pending=repo.create(owner,new DependencySpec(DependencySpec.Kind.GITHUB_PR_MERGED,"owner/repo#17",null),Duration.ofHours(1),List.of());
    var facts=new GitHubFactRepository(source,clock);var probe=new DependencySourceProbe(null,null,null,null,clock);probe.setGitHubFacts(facts);
    assertThat(probe.probe(pending).state()).isEqualTo(DependencyState.WAITING);
    var props=new GitHubWebhookProperties();props.setSecret(GitHubWebhookDecoderTest.SECRET);props.setMappings(List.of(new GitHubWebhookProperties.Mapping("owner/repo",project.id(),session,"main",17,true)));
    var inbox=new AttentionRepository(source,clock);var receiver=new GitHubWebhookService(props,clock,projects,sessions,facts,new PersistentAgentScheduler(source,clock),inbox,null);
    var bytes=GitHubWebhookDecoderTest.body("\"action\":\"closed\",\"pull_request\":{\"number\":17,\"base\":{\"ref\":\"main\"},\"head\":{\"sha\":\""+"a".repeat(40)+"\"},\"merged\":true,\"merged_at\":\"2026-10-07T04:00:00Z\"}");
    assertThatThrownBy(()->receiver.receive(UUID.randomUUID().toString(),"pull_request","sha256="+"0".repeat(64),bytes)).isInstanceOf(SecurityException.class);
    assertThat(probe.probe(pending).state()).isEqualTo(DependencyState.WAITING);
    receiver.receive(UUID.randomUUID().toString(),"pull_request",GitHubWebhookDecoderTest.signature(bytes),bytes);
    assertThat(probe.probe(pending).state()).isEqualTo(DependencyState.COMPLETED);
    assertThat(inbox.list(project.id())).hasSize(1);
    var foreign=repo.create(new AgentRunContext("other","other-session",root,project.id()),new DependencySpec(DependencySpec.Kind.GITHUB_PR_MERGED,"owner/repo#17",null),Duration.ofHours(1),List.of());
    assertThat(probe.probe(foreign).state()).isEqualTo(DependencyState.WAITING);
    var different=repo.create(owner,new DependencySpec(DependencySpec.Kind.GITHUB_PR_MERGED,"owner/repo#18",null),Duration.ofHours(1),List.of());
    assertThat(probe.probe(different).state()).isEqualTo(DependencyState.WAITING);
    assertThatThrownBy(()->new DependencySpec(DependencySpec.Kind.GITHUB_PR_MERGED,"../../repo#17",null)).isInstanceOf(IllegalArgumentException.class);
  }
}
