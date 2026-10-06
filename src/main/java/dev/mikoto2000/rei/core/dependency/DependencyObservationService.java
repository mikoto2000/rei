package dev.mikoto2000.rei.core.dependency;

import java.nio.file.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import dev.mikoto2000.rei.core.policy.*;
import dev.mikoto2000.rei.core.project.ProjectService;
import dev.mikoto2000.rei.application.session.SessionRepository;
import dev.mikoto2000.rei.event.*;

/** Bounded observation and durable at-least-once facts. No action execution or automatic replays. */
@Component
@EnableConfigurationProperties(DependencyWatcherProperties.class)
public class DependencyObservationService {
  private final PersistentDependencyRepository repo;private final DependencyProbe probe;
  private final DependencyWatcherProperties properties;private final ToolPermissionPolicy policy;
  private final AgentEventPublisher publisher;private final ProjectService projects;private final SessionRepository sessions;private String after="";
  public DependencyObservationService(PersistentDependencyRepository repo,DependencyProbe probe,DependencyWatcherProperties properties,ToolPermissionPolicy policy,AgentEventPublisher publisher) {
    this(repo,probe,properties,policy,publisher,null,null);
  }
  @Autowired public DependencyObservationService(PersistentDependencyRepository repo,DependencyProbe probe,DependencyWatcherProperties properties,ToolPermissionPolicy policy,AgentEventPublisher publisher,ProjectService projects,SessionRepository sessions) {
    this.repo=repo;this.probe=probe;this.properties=properties;this.policy=policy;this.publisher=publisher;this.projects=projects;this.sessions=sessions;
  }
  public PersistentDependencyRepository.Entry inspect(String project,String id,boolean network) {
    var entry=repo.prepare(project,id);
    if(entry.spec().network()&&!network)throw new IllegalArgumentException("HTTP observation requires the network tool/control");
    if(PersistentDependencyRepository.terminal(entry.state())||entry.reason().equals("dependency_waiting")||entry.reason().equals("dependency_failed")){flushFacts();return entry;}
    if(!validProject(entry)){entry=repo.observe(entry,DependencyState.BLOCKED,"owner_unavailable");flushFacts();return entry;}
    var observed=probe.probe(entry);
    if(!observed.id().equals(entry.id()))throw new IllegalStateException("Probe identity mismatch");
    var updated=repo.observe(entry,observed.state(),observed.detail());flushFacts();return updated;
  }
  private boolean validProject(PersistentDependencyRepository.Entry entry) {
    try {
      var root=Path.of(entry.projectRoot());
      return Files.isDirectory(root)&&root.toRealPath().equals(root)&&(projects==null||projects.registeredProjects().stream().anyMatch(p->p.id().equals(entry.projectId())&&p.root().equals(root)))
          &&(sessions==null||sessions.findById(entry.sessionId()).filter(session->entry.projectId().equals(session.projectId())).isPresent());
    }catch(java.io.IOException|IllegalArgumentException error){return false;}
  }
  @Scheduled(fixedDelayString="${rei.dependency-watcher.check-interval-ms:5000}")
  public synchronized void tick() {
    flushFacts();
    if(!properties.enabled()||!policy.enforced())return;
    var entries=repo.activeAfter(after);if(entries.isEmpty()){after="";entries=repo.activeAfter(after);}
    for(var saved:entries) {
      after=saved.id();
      try {
        var entry=repo.prepare(saved.projectId(),saved.id());if(PersistentDependencyRepository.terminal(entry.state())||entry.reason().equals("dependency_waiting")||entry.reason().equals("dependency_failed"))continue;
        boolean http=entry.spec().network();
        var permission=policy.evaluate(http?"checkHttpDependency":"checkDependency");
        if(permission!=PermissionDecision.AUTO_APPROVE)repo.observe(entry,DependencyState.BLOCKED,permission==PermissionDecision.DENY?"permission_denied":"permission_required");
        else inspect(entry.projectId(),entry.id(),http);
      }catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}
      catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dependency observation failed: {}",error.getClass().getSimpleName());}
    }
    flushFacts();
  }
  public synchronized void flushFacts() {
    for(var fact:repo.pendingFacts()) {
      var type=switch(fact.state()){case COMPLETED->AgentEventType.DEPENDENCY_COMPLETED;case FAILED->AgentEventType.DEPENDENCY_FAILED;case CANCELLED->AgentEventType.DEPENDENCY_CANCELLED;default->AgentEventType.DEPENDENCY_UPDATED;};
      var event=new AgentEvent(fact.eventId(),0,fact.timestamp(),type,1,fact.sessionId(),null,null,fact.dependencyId(),null,
          new DependencyStatusPayload(fact.dependencyId(),fact.kind(),fact.state().name(),fact.reason(),fact.version()),fact.projectId());
      try {publisher.publishBoundary(event);repo.ackFact(fact.eventId());}
      catch(RuntimeException error){org.slf4j.LoggerFactory.getLogger(getClass()).warn("Dependency fact delivery failed: {}",error.getClass().getSimpleName());return;}
    }
  }
}
