package dev.mikoto2000.rei.github;
import java.time.Duration;
import java.util.*;
@org.springframework.boot.context.properties.ConfigurationProperties("rei.github.webhook")
@lombok.Getter @lombok.Setter
public class GitHubWebhookProperties {
  private boolean enabled;
  private String secret;
  private int maxPayloadBytes=262144,maxDeliveries=10000;
  private int maxFacts=50000;
  private boolean recheckRequired;
  private Duration maxAge=Duration.ofDays(7),futureSkew=Duration.ofMinutes(5);
  private Set<String> events=Set.of("pull_request","pull_request_review","workflow_run");
  private List<Mapping> mappings=List.of();
  public record Mapping(String repository,String projectId,String sessionId,String branch,Integer pullRequest,boolean notifyReview) {}
  public void validateTransport() {
    if(secret==null||secret.length()<32||secret.length()>4096||secret.chars().anyMatch(Character::isISOControl)
        ||maxPayloadBytes<1024||maxPayloadBytes>1024*1024||maxDeliveries<1||maxDeliveries>100000
        ||maxFacts<1||maxFacts>100000||maxAge==null||maxAge.compareTo(Duration.ofMinutes(1))<0||maxAge.compareTo(Duration.ofDays(30))>0
        ||futureSkew==null||futureSkew.isNegative()||futureSkew.compareTo(Duration.ofMinutes(5))>0
        ||events==null||events.isEmpty()||!Set.of("pull_request","pull_request_review","workflow_run").containsAll(events))
      throw new IllegalArgumentException("Invalid GitHub webhook configuration");
  }
  public void validateMappings() {
    validateTransport();if(mappings==null||mappings.isEmpty()||mappings.size()>64)throw new IllegalArgumentException("Explicit GitHub repository mappings required");
    var identities=new HashSet<String>();
    for(var mapping:mappings) {
      if(mapping==null||mapping.repository()==null||!mapping.repository().matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}")
          ||mapping.projectId()==null||mapping.projectId().isBlank()||mapping.projectId().length()>128
          ||mapping.sessionId()==null||mapping.sessionId().isBlank()||mapping.sessionId().length()>256
          ||mapping.branch()!=null&&(mapping.branch().isBlank()||mapping.branch().length()>256||mapping.branch().chars().anyMatch(Character::isISOControl))
          ||mapping.pullRequest()!=null&&mapping.pullRequest()<1
          ||!identities.add(mapping.repository().toLowerCase(Locale.ROOT)+"\0"+mapping.projectId()+"\0"+mapping.sessionId()+"\0"+mapping.branch()+"\0"+mapping.pullRequest()))
        throw new IllegalArgumentException("Invalid or duplicate GitHub mapping");
    }
  }
}
