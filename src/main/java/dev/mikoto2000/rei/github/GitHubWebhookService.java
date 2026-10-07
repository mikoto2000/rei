package dev.mikoto2000.rei.github;
import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import dev.mikoto2000.rei.application.session.SessionLifecycle;
import dev.mikoto2000.rei.application.run.ResourceNotFoundException;
import dev.mikoto2000.rei.application.state.OperationConflictException;
import dev.mikoto2000.rei.core.project.*;
import dev.mikoto2000.rei.temporal.PersistentAgentScheduler;
import dev.mikoto2000.rei.attention.AttentionRepository;
/** Signed facts activate existing reviewed continuations, never commands from webhook text. */
public final class GitHubWebhookService {
  public record MappingView(String repository,String projectId,String sessionId,String branch,Integer pullRequest,boolean notifyReview,String sourceId) {}
  private record Owner(GitHubWebhookProperties.Mapping mapping,ProjectContext project) {}
  private final GitHubWebhookDecoder decoder;private final GitHubWebhookProperties properties;private final ProjectRegistry projects;
  private final SessionLifecycle sessions;private final GitHubFactRepository facts;private final PersistentAgentScheduler scheduler;
  private final AttentionRepository inbox;private final GitHubStateVerifier verifier;private final List<Owner> owners;
  private final Clock clock;
  public GitHubWebhookService(GitHubWebhookProperties properties,Clock clock,ProjectRegistry projects,SessionLifecycle sessions,
      GitHubFactRepository facts,PersistentAgentScheduler scheduler,AttentionRepository inbox,GitHubStateVerifier verifier) {
    properties.validateMappings();this.decoder=new GitHubWebhookDecoder(properties,clock);this.properties=properties;this.projects=projects;
    this.sessions=sessions;this.facts=facts;this.scheduler=scheduler;this.inbox=inbox;this.verifier=verifier;
    this.clock=clock;
    if(properties.isRecheckRequired()&&verifier==null)throw new IllegalArgumentException("GitHub state recheck is required but unavailable");
    owners=properties.getMappings().stream().map(mapping->{var project=projects.resolveById(mapping.projectId()).orElseThrow(()->new IllegalArgumentException("GitHub mapping Project not registered"));sessions.validate(mapping.sessionId(),project.id());return new Owner(mapping,project);}).toList();
  }
  public synchronized GitHubFactRepository.Receipt receive(String delivery,String event,String signature,byte[] bytes) {
    try{if(delivery==null||!UUID.fromString(delivery).toString().equalsIgnoreCase(delivery))throw new IllegalArgumentException();delivery=delivery.toLowerCase(Locale.ROOT);}
    catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Canonical GitHub delivery ID required");}
    bytes=bytes.clone();
    var decoded=decoder.decode(event,signature,bytes);
    final String digest;
    try{digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    var duplicate=facts.duplicate(delivery,digest);if(duplicate.isPresent())return duplicate.get();
    var accepted=new ArrayList<GitHubFactRepository.OwnedFact>();var notifications=new HashSet<String>();
    for(var owner:owners)for(var fact:decoded) {
      var rule=owner.mapping();
      if(!rule.repository().equalsIgnoreCase(fact.repository())||rule.branch()!=null&&!rule.branch().equals(fact.branch())
          ||rule.pullRequest()!=null&&!rule.pullRequest().equals(fact.pullRequest()))continue;
      if(!projects.resolveById(owner.project().id()).filter(current->current.root().equals(owner.project().root())).isPresent())throw new ResourceNotFoundException("GitHub mapping");
      sessions.validate(rule.sessionId(),rule.projectId());
      if(properties.isRecheckRequired()&&verifier.verify(fact)!=GitHubStateVerifier.Verdict.CONFIRMED)throw new OperationConflictException();
      String id=UUID.nameUUIDFromBytes((delivery+"\0"+rule.projectId()+"\0"+rule.sessionId()+"\0"+fact.sourceId()).getBytes(StandardCharsets.UTF_8)).toString();
      accepted.add(new GitHubFactRepository.OwnedFact(id,delivery,owner.project(),rule.sessionId(),fact));
      if(fact.type().equals("CI_FAILED")||fact.type().equals("PR_MERGED")||fact.type().equals("REVIEW_SUBMITTED")&&rule.notifyReview())notifications.add(id);
    }
    var unique=new LinkedHashMap<String,GitHubFactRepository.OwnedFact>();for(var fact:accepted)unique.putIfAbsent(fact.id(),fact);
    return facts.accept(delivery,digest,event,List.copyOf(unique.values()),properties.getMaxDeliveries(),properties.getMaxFacts(),owned->{
      var envelope=owned.event(clock.instant());
      scheduler.signalExternalEvent(envelope,owned.project().root());
      if(notifications.contains(owned.id()))inbox.createGitHub(envelope,"GITHUB_"+owned.fact().type(),"GitHub "+owned.fact().type()+": "+owned.fact().repository()+" #"+Objects.toString(owned.fact().pullRequest(),"branch")+". Inspect the saved fact; webhook text is not an Agent instruction.").ifPresent(item->facts.enqueueNotification(owned,item));
    });
  }
  private ProjectContext owned(String project,String session) {
    var registered=projects.resolveById(project).orElseThrow(()->new ResourceNotFoundException("GitHub mapping"));
    if(owners.stream().noneMatch(owner->owner.project().id().equals(project)&&owner.project().root().equals(registered.root())&&owner.mapping().sessionId().equals(session)))throw new ResourceNotFoundException("GitHub mapping");
    try{sessions.validate(session,project);}catch(dev.mikoto2000.rei.application.run.SessionConflictException foreign){throw new ResourceNotFoundException("GitHub mapping");}
    return registered;
  }
  public List<MappingView> mappings(String project,String session) {
    owned(project,session);
    return owners.stream().map(Owner::mapping).filter(rule->rule.projectId().equals(project)&&rule.sessionId().equals(session))
        .map(rule->new MappingView(rule.repository(),project,session,rule.branch(),rule.pullRequest(),rule.notifyReview(),rule.pullRequest()!=null||rule.branch()!=null?GitHubFact.sourceId(rule.repository(),rule.pullRequest(),rule.branch()):null)).toList();
  }
  public List<GitHubFactRepository.View> forDelivery(String project,String session,String delivery){return facts.forDelivery(owned(project,session),session,delivery);}
  public GitHubFactRepository.View get(String project,String session,String id){return facts.get(owned(project,session),session,id);}
}
