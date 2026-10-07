package dev.mikoto2000.rei.github;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
/** Verify raw bytes before parsing a bounded tree; strip all free-form Agent instructions. */
public final class GitHubWebhookDecoder {
  private final GitHubWebhookProperties properties;private final Clock clock;private final ObjectMapper json;
  public GitHubWebhookDecoder(GitHubWebhookProperties properties,Clock clock) {
    properties.validateTransport();this.properties=properties;this.clock=clock;
    var factory=JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
        .maxStringLength(properties.getMaxPayloadBytes()).maxNumberLength(32).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    json=new ObjectMapper(factory).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  }
  public List<GitHubFact> decode(String event,String signature,byte[] bytes) {
    if(bytes==null||bytes.length>properties.getMaxPayloadBytes())throw new IllegalArgumentException("GitHub payload limit");
    verify(signature,bytes);
    if(event==null||!properties.getEvents().contains(event))throw new IllegalArgumentException("Unsupported GitHub event");
    JsonNode root;
    try{root=json.readTree(bytes);}catch(java.io.IOException invalid){throw new IllegalArgumentException("Invalid GitHub JSON");}
    if(root==null||!root.isObject())throw new IllegalArgumentException("GitHub object required");
    String repository=text(root.path("repository"),"full_name",201);
    if(!repository.matches("[A-Za-z0-9_.-]{1,100}/[A-Za-z0-9_.-]{1,100}"))throw new IllegalArgumentException("Invalid repository");
    repository=repository.toLowerCase(Locale.ROOT);String action=text(root,"action",64);
    if(event.equals("workflow_run")) {
      if(!root.path("workflow_run").isObject()||root.has("review")||root.has("pull_request"))throw new IllegalArgumentException("GitHub event header differs from payload");
      if(!action.equals("completed"))return List.of();
      var workflow=root.path("workflow_run");String status=text(workflow,"status",32);
      if(!status.equals("completed"))throw new IllegalArgumentException("Incomplete workflow");
      String conclusion=text(workflow,"conclusion",32);
      if(!Set.of("success","failure","neutral","cancelled","skipped","timed_out","action_required","startup_failure","stale").contains(conclusion))throw new IllegalArgumentException("Invalid workflow conclusion");
      String type=Set.of("failure","timed_out","action_required","startup_failure").contains(conclusion)?"CI_FAILED":"WORKFLOW_COMPLETED";
      String branch=text(workflow,"head_branch",256),sha=sha(workflow,"head_sha");Instant at=time(workflow,"updated_at");
      if(!workflow.path("id").isIntegralNumber()||!workflow.path("id").canConvertToLong()||workflow.path("id").longValue()<1)throw new IllegalArgumentException("Workflow identity required");
      long run=workflow.path("id").longValue();var requests=workflow.path("pull_requests");
      if(!requests.isArray()||requests.size()>16)throw new IllegalArgumentException("Bounded workflow pull requests required");
      var facts=new ArrayList<GitHubFact>();var numbers=new HashSet<Integer>();
      for(var request:requests){int number=number(request,"number");if(!numbers.add(number))throw new IllegalArgumentException("Duplicate pull request");facts.add(new GitHubFact(type,event,action,repository,branch,number,sha,conclusion,run,at));}
      if(facts.isEmpty())facts.add(new GitHubFact(type,event,action,repository,branch,null,sha,conclusion,run,at));
      return List.copyOf(facts);
    }
    var pr=root.path("pull_request");int number=number(pr,"number");
    if(!pr.isObject()||root.has("workflow_run")||event.equals("pull_request")&&root.has("review"))throw new IllegalArgumentException("GitHub event header differs from payload");
    String branch=text(pr.path("base"),"ref",256),sha=sha(pr.path("head"));
    if(event.equals("pull_request_review")) {
      if(!root.path("review").isObject())throw new IllegalArgumentException("Review fact required");
      if(!action.equals("submitted"))return List.of();
      var review=root.path("review");String state=text(review,"state",32);
      if(!Set.of("approved","changes_requested","commented").contains(state))throw new IllegalArgumentException("Unsupported review state");
      return List.of(new GitHubFact("REVIEW_SUBMITTED",event,action,repository,branch,number,sha,state,null,time(review,"submitted_at")));
    }
    if(!Set.of("opened","reopened","closed","synchronize","edited","ready_for_review","converted_to_draft","labeled","unlabeled","assigned","unassigned","review_requested","review_request_removed","locked","unlocked","milestoned","demilestoned","auto_merge_enabled","auto_merge_disabled","enqueued","dequeued").contains(action))return List.of();
    if(!pr.path("merged").isBoolean())throw new IllegalArgumentException("Pull request merge state required");
    boolean merged=pr.path("merged").booleanValue();
    if(merged&&!action.equals("closed"))throw new IllegalArgumentException("Invalid merged event");
    return List.of(new GitHubFact(merged?"PR_MERGED":"PR_UPDATED",event,action,repository,branch,number,sha,null,null,time(pr,merged?"merged_at":"updated_at")));
  }
  private void verify(String signature,byte[] bytes) {
    if(signature==null||!signature.matches("sha256=[a-f0-9]{64}"))throw new SecurityException("Invalid GitHub signature");
    try {
      var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(properties.getSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
      if(!MessageDigest.isEqual(mac.doFinal(bytes),HexFormat.of().parseHex(signature.substring(7))))throw new SecurityException("Invalid GitHub signature");
    }catch(GeneralSecurityException unavailable){throw new IllegalStateException("GitHub signature verifier unavailable");}
  }
  private Instant time(JsonNode object,String field) {
    final Instant at;
    try{at=OffsetDateTime.parse(text(object,field,40)).toInstant();}catch(DateTimeException invalid){throw new IllegalArgumentException("Invalid GitHub event timestamp");}
    if(at.isBefore(clock.instant().minus(properties.getMaxAge()))||at.isAfter(clock.instant().plus(properties.getFutureSkew())))throw new IllegalArgumentException("GitHub event outside replay window");
    return at;
  }
  private static String sha(JsonNode object){return sha(object,"sha");}
  private static String sha(JsonNode object,String field){String sha=text(object,field,64);if(!sha.matches("[a-fA-F0-9]{40}|[a-fA-F0-9]{64}"))throw new IllegalArgumentException("Invalid GitHub commit");return sha.toLowerCase(Locale.ROOT);}
  private static int number(JsonNode object,String field){var value=object.path(field);if(!value.isIntegralNumber()||!value.canConvertToInt()||value.intValue()<1)throw new IllegalArgumentException("Positive GitHub number required");return value.intValue();}
  private static String text(JsonNode object,String field,int limit){var node=object.path(field);if(!node.isTextual()||node.textValue().isBlank()||node.textValue().length()>limit||node.textValue().chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid GitHub fact field");return node.textValue();}
}
