package dev.mikoto2000.rei.github;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.application.task.*;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.temporal.*;
import dev.mikoto2000.rei.attention.*;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.event.AgentEvent;
import static org.assertj.core.api.Assertions.*;
@Tag("integration")
class GitHubWebhookServiceTest {
  @TempDir Path root;
  static final Clock CLOCK=Clock.fixed(GitHubWebhookDecoderTest.NOW,ZoneOffset.UTC);
  static class Sessions implements SessionRepository {
    final Map<String,SessionMetadata> rows=new HashMap<>();
    public Optional<SessionMetadata> findById(String id){return Optional.ofNullable(rows.get(id));}
    public List<SessionMetadata> findPage(String project,CursorKey key,int count){return rows.values().stream().filter(s->s.projectId().equals(project)).limit(count).toList();}
    public void accept(SessionMetadata session,Runnable enqueue){rows.put(session.sessionId(),session);enqueue.run();}
  }
  @Test void verifiedFailedCiSignalsOnlyTheExistingOwnedTriggerAndSurvivesDuplicateRestart() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var sessions=new SessionLifecycle(new Sessions(),CLOCK);var session=sessions.create(project,"CI").sessionId();
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var scheduler=new PersistentAgentScheduler(ds,CLOCK);var inbox=new AttentionRepository(ds,CLOCK);
    var owner=new AgentRunContext("owner",session,root,project.id());String schedule;
    try(var scope=AgentRunScope.open(owner)) {
      schedule=scheduler.scheduleOnEvent(GitHubFact.sourceId("owner/repo",17,"main"),AgentEventType.GITHUB_CI_FAILED,Duration.ofHours(1),"Inspect the CI using read-only tools",session).id();
    }
    scheduler.activate(project.id(),schedule);
    var props=new GitHubWebhookProperties();props.setSecret(GitHubWebhookDecoderTest.SECRET);
    props.setMappings(List.of(new GitHubWebhookProperties.Mapping("owner/repo",project.id(),session,"main",17,true)));
    var facts=new GitHubFactRepository(ds,CLOCK);var receiver=new GitHubWebhookService(props,CLOCK,projects,sessions,facts,scheduler,inbox,null);
    var bytes=workflow("success",17);receiver.receive(UUID.randomUUID().toString(),"workflow_run",GitHubWebhookDecoderTest.signature(bytes),bytes);
    assertThat(scheduler.get(project.id(),schedule).status()).isEqualTo("WAITING_EVENT");
    bytes=workflow("failure",18);receiver.receive(UUID.randomUUID().toString(),"workflow_run",GitHubWebhookDecoderTest.signature(bytes),bytes);
    assertThat(scheduler.get(project.id(),schedule).status()).isEqualTo("WAITING_EVENT");
    bytes=workflow("failure",17);String delivery=UUID.randomUUID().toString();
    assertThat(receiver.receive(delivery,"workflow_run",GitHubWebhookDecoderTest.signature(bytes),bytes).duplicate()).isFalse();
    assertThat(scheduler.get(project.id(),schedule).status()).isEqualTo("SCHEDULED");
    assertThat(inbox.list(project.id())).hasSize(1);
    var restarted=new GitHubWebhookService(props,CLOCK,projects,sessions,new GitHubFactRepository(ds,CLOCK),scheduler,inbox,null);
    assertThat(restarted.receive(delivery,"workflow_run",GitHubWebhookDecoderTest.signature(bytes),bytes).duplicate()).isTrue();
    assertThat(restarted.receive(UUID.randomUUID().toString(),"workflow_run",GitHubWebhookDecoderTest.signature(bytes),bytes).duplicate()).isTrue();
    assertThat(inbox.list(project.id())).hasSize(1);
    var tasks=new TaskManagerService(projects,new RunRegistry(CLOCK),null,null,null,scheduler);
    assertThat(tasks.get(project.id(),session,"schedule:"+schedule).status()).isEqualTo("QUEUED");
    assertThat(tasks.get(project.id(),session,"schedule:"+schedule).results()).filteredOn(r->r.kind().equals("GITHUB_EVENT")).extracting(TaskView.Reference::id)
        .containsExactly(scheduler.eventTrigger(project.id(),schedule).orElseThrow().matchedEventId());
    assertThat(scheduler.get(project.id(),schedule).task().action()).doesNotContain("workflow_run");
  }
  @Test void failedInboxWriteRollsBackReceiptFactAndTriggerTogetherBeforeAnExplicitRetry() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var sessions=new SessionLifecycle(new Sessions(),CLOCK);var session=sessions.create(project,"CI").sessionId();
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var scheduler=new PersistentAgentScheduler(ds,CLOCK);var inbox=org.mockito.Mockito.spy(new AttentionRepository(ds,CLOCK));
    String schedule;
    try(var scope=AgentRunScope.open(new AgentRunContext("owner",session,root,project.id()))) {
      schedule=scheduler.scheduleOnEvent(GitHubFact.sourceId("owner/repo",17,"main"),AgentEventType.GITHUB_CI_FAILED,Duration.ofHours(1),"Inspect CI",session).id();
    }
    scheduler.activate(project.id(),schedule);var facts=new GitHubFactRepository(ds,CLOCK);
    var props=new GitHubWebhookProperties();props.setSecret(GitHubWebhookDecoderTest.SECRET);props.setMappings(List.of(new GitHubWebhookProperties.Mapping("owner/repo",project.id(),session,"main",17,true)));
    var receiver=new GitHubWebhookService(props,CLOCK,projects,sessions,facts,scheduler,inbox,null);
    org.mockito.Mockito.doThrow(new IllegalStateException("fixture storage failure")).when(inbox).createGitHub(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    var bytes=workflow("failure",17);String delivery=UUID.randomUUID().toString();String signature=GitHubWebhookDecoderTest.signature(bytes);
    assertThatThrownBy(()->receiver.receive(delivery,"workflow_run",signature,bytes)).isInstanceOf(IllegalStateException.class);
    assertThat(scheduler.get(project.id(),schedule).status()).isEqualTo("WAITING_EVENT");
    assertThatThrownBy(()->facts.forDelivery(project,session,delivery)).isInstanceOf(ResourceNotFoundException.class);
    org.mockito.Mockito.doCallRealMethod().when(inbox).createGitHub(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString());
    assertThat(receiver.receive(delivery,"workflow_run",signature,bytes).duplicate()).isFalse();
    assertThat(scheduler.get(project.id(),schedule).status()).isEqualTo("SCHEDULED");assertThat(inbox.list(project.id())).hasSize(1);
  }
  @Test void committedNotificationSurvivesPublisherFailureAndRestartWithoutCreatingAnotherInboxItem() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var sessions=new SessionLifecycle(new Sessions(),CLOCK);var session=sessions.create(project,"CI").sessionId();
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var scheduler=new PersistentAgentScheduler(ds,CLOCK);var inbox=new AttentionRepository(ds,CLOCK);
    var props=new GitHubWebhookProperties();props.setSecret(GitHubWebhookDecoderTest.SECRET);
    props.setMappings(List.of(new GitHubWebhookProperties.Mapping("owner/repo",project.id(),session,"main",17,true)));
    var facts=new GitHubFactRepository(ds,CLOCK);
    var receiver=new GitHubWebhookService(props,CLOCK,projects,sessions,facts,scheduler,inbox,null);
    var bytes=workflow("failure",17);String delivery=UUID.randomUUID().toString();
    receiver.receive(delivery,"workflow_run",GitHubWebhookDecoderTest.signature(bytes),bytes);
    var pending=facts.pendingNotifications();assertThat(pending).hasSize(1);
    var failed=new GitHubNotificationOutbox(facts,projects,event->{throw new IllegalStateException("fixture publisher failure");});
    failed.flush();assertThat(facts.pendingNotifications()).hasSize(1);
    var restarted=new GitHubFactRepository(ds,CLOCK);var published=new ArrayList<AgentEvent>();
    var outbox=new GitHubNotificationOutbox(restarted,projects,published::add);outbox.flush();outbox.flush();
    assertThat(published).hasSize(1);assertThat(published.getFirst().type()).isEqualTo(AgentEventType.ATTENTION_REQUIRED);
    assertThat(published.getFirst().correlationId()).isEqualTo(inbox.list(project.id()).getFirst().id());
    assertThat(restarted.pendingNotifications()).isEmpty();assertThat(inbox.list(project.id())).hasSize(1);
  }
  @Test void requiredRecheckFailsClosedAndDuplicateDoesNotRecheckOrConsumeCapacity() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var project=projects.resolve(root);
    var sessions=new SessionLifecycle(new Sessions(),CLOCK);var session=sessions.create(project,"CI").sessionId();
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));
    var scheduler=new PersistentAgentScheduler(ds,CLOCK);var inbox=new AttentionRepository(ds,CLOCK);
    var props=new GitHubWebhookProperties();props.setSecret(GitHubWebhookDecoderTest.SECRET);props.setRecheckRequired(true);props.setMaxDeliveries(1);props.setMaxFacts(1);
    props.setMappings(List.of(new GitHubWebhookProperties.Mapping("owner/repo",project.id(),session,"main",17,true)));
    var facts=new GitHubFactRepository(ds,CLOCK);
    assertThatThrownBy(()->new GitHubWebhookService(props,CLOCK,projects,sessions,facts,scheduler,inbox,null)).isInstanceOf(IllegalArgumentException.class);
    var calls=new java.util.concurrent.atomic.AtomicInteger();var verdict=new java.util.concurrent.atomic.AtomicReference<>(GitHubStateVerifier.Verdict.UNAVAILABLE);
    var receiver=new GitHubWebhookService(props,CLOCK,projects,sessions,facts,scheduler,inbox,fact->{calls.incrementAndGet();return verdict.get();});
    var bytes=workflow("failure",17);var signature=GitHubWebhookDecoderTest.signature(bytes);String delivery=UUID.randomUUID().toString();
    assertThatThrownBy(()->receiver.receive(delivery,"workflow_run",signature,bytes)).isInstanceOf(dev.mikoto2000.rei.application.state.OperationConflictException.class);
    assertThat(inbox.list(project.id())).isEmpty();assertThat(facts.pendingNotifications()).isEmpty();
    verdict.set(GitHubStateVerifier.Verdict.CONFIRMED);assertThat(receiver.receive(delivery,"workflow_run",signature,bytes).duplicate()).isFalse();
    verdict.set(GitHubStateVerifier.Verdict.REJECTED);assertThat(receiver.receive(UUID.randomUUID().toString(),"workflow_run",signature,bytes).duplicate()).isTrue();assertThat(calls.get()).isEqualTo(2);
    var changed=workflow("success",17);
    assertThatThrownBy(()->receiver.receive(delivery,"workflow_run",GitHubWebhookDecoderTest.signature(changed),changed)).isInstanceOf(dev.mikoto2000.rei.application.state.OperationConflictException.class);
    verdict.set(GitHubStateVerifier.Verdict.CONFIRMED);
    assertThatThrownBy(()->receiver.receive(UUID.randomUUID().toString(),"workflow_run",GitHubWebhookDecoderTest.signature(changed),changed)).isInstanceOf(GitHubFactRepository.CapacityExceeded.class);
    assertThat(inbox.list(project.id())).hasSize(1);assertThat(facts.pendingNotifications()).hasSize(1);
  }
  @Test void obsoleteProjectRootsNeverPublishAndDoNotStarveOtherProjects() throws Exception {
    var projects=new ProjectRegistry(root.resolve("projects.json"));var old=projects.resolve(root);
    var good=projects.resolve(Files.createDirectory(root.resolve("good")));
    var ds=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("state.db"));var facts=new GitHubFactRepository(ds,CLOCK);
    var fact=new GitHubFact("CI_FAILED","workflow_run","completed","owner/repo","main",17,"b".repeat(40),"failure",42L,CLOCK.instant());
    var inbox=new AttentionRepository(ds,CLOCK);
    for(int i=0;i<16;i++) {
      var owned=new GitHubFactRepository.OwnedFact("old-"+i,UUID.randomUUID().toString(),old,"session",fact);
      facts.enqueueNotification(owned,inbox.createGitHub(owned.event(CLOCK.instant()),"GITHUB_CI_FAILED","fixture").orElseThrow());
    }
    var newInbox=new AttentionRepository(ds,Clock.offset(CLOCK,Duration.ofMillis(1)));
    var owned=new GitHubFactRepository.OwnedFact("good",UUID.randomUUID().toString(),good,"session",fact);
    facts.enqueueNotification(owned,newInbox.createGitHub(owned.event(CLOCK.instant()),"GITHUB_CI_FAILED","fixture").orElseThrow());
    projects.relocate(old.id(),Files.createDirectory(root.resolve("moved")));
    var published=new ArrayList<AgentEvent>();var outbox=new GitHubNotificationOutbox(facts,projects,published::add);
    outbox.flush();assertThat(published).isEmpty();assertThat(facts.pendingNotifications()).hasSize(1);
    outbox.flush();assertThat(published).hasSize(1);assertThat(published.getFirst().projectId()).isEqualTo(good.id());
    assertThat(facts.pendingNotifications()).isEmpty();
  }
  static byte[] workflow(String conclusion,int pr) {
    return GitHubWebhookDecoderTest.body("\"action\":\"completed\",\"workflow_run\":{\"id\":42,\"head_branch\":\"main\",\"head_sha\":\""+"b".repeat(40)+"\",\"status\":\"completed\",\"conclusion\":\""+conclusion+"\",\"updated_at\":\"2026-10-07T04:00:00Z\",\"pull_requests\":[{\"number\":"+pr+"}]}");
  }
}
