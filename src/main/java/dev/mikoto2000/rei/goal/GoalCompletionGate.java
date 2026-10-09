package dev.mikoto2000.rei.goal;

import java.io.IOException;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import dev.mikoto2000.rei.core.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.artifact.*;
import dev.mikoto2000.rei.event.CredentialRedactor;
import org.springframework.stereotype.Service;

/** Additional independent checks in the existing FileGoalVerifier, never a separate execution loop. */
@Service
public final class GoalCompletionGate {
  public record Reference(String id,String sha256) {}
  public record RequiredTests(String commandSha256,List<String> tests,String testCommand){
    public RequiredTests(String commandSha256,List<String> tests){this(commandSha256,tests,null);}
    public RequiredTests{tests=tests==null?List.of():List.copyOf(tests);if(commandSha256==null&&testCommand!=null)commandSha256=commandHash(testCommand);}
  }
  public record ArtifactRequirement(String filename,String mediaType,String sha256,boolean deliveryRequired) {
    public ArtifactRequirement(String filename,String mediaType,String sha256){this(filename,mediaType,sha256,false);}
  }
  public record Requirement(String id,String statement,Boolean required,GoalRepository.FileCriterion criterion) { public Requirement{if(required==null)throw new IllegalArgumentException("Explicit required true/false required");} }
  public record ReviewGate(boolean semanticRequired,List<SemanticPatchReviewService.Requirement> requirements){public ReviewGate{requirements=requirements==null?List.of():List.copyOf(requirements);}}
  public record Definition(List<GoalRepository.FileCriterion> completionEvidence,RequiredTests requiredTests,
      List<ArtifactRequirement> requiredArtifacts,List<GoalRepository.FileCriterion> requiredPredicates,ReviewGate reviewGate,List<Requirement> requirements){
    public Definition(List<GoalRepository.FileCriterion> evidence,RequiredTests tests,List<ArtifactRequirement> artifacts,List<GoalRepository.FileCriterion> predicates,ReviewGate review){this(evidence,tests,artifacts,predicates,review,List.of());}
    public Definition{requirements=requirements==null?List.of():List.copyOf(requirements);completionEvidence=completionEvidence==null?List.of():List.copyOf(completionEvidence);requiredArtifacts=requiredArtifacts==null?List.of():List.copyOf(requiredArtifacts);requiredPredicates=requiredPredicates==null?List.of():List.copyOf(requiredPredicates);}
  }
  public record Proof(Reference review,List<Reference> artifacts,List<Reference> deliveredArtifacts){
    public Proof(Reference review,List<Reference> artifacts){this(review,artifacts,List.of());}
    public Proof{artifacts=artifacts==null?List.of():List.copyOf(artifacts);deliveredArtifacts=deliveredArtifacts==null?List.of():List.copyOf(deliveredArtifacts);}
  }
  @FunctionalInterface public interface ReviewReader{SemanticPatchReviewService.Receipt read(AgentRunContext owner,Reference reference)throws IOException;}
  @FunctionalInterface public interface ArtifactReader{Artifact read(AgentRunContext owner,Reference reference)throws IOException;}
  private final GoalRepository goals;private final ReviewReader reviews;private final ArtifactReader artifacts;
  private final SelfPatchReviewService.Capture capture;private final Clock clock;private final boolean requireAll;
  @org.springframework.beans.factory.annotation.Autowired
  public GoalCompletionGate(GoalRepository goals,SemanticPatchReviewService reviews,org.springframework.beans.factory.ObjectProvider<ArtifactStore> artifacts,Clock clock,
      @org.springframework.beans.factory.annotation.Value("${rei.goal.completion-gate.require-all:false}") boolean requireAll){
    this(goals,(owner,ref)->reviews.get(owner,ref.id(),ref.sha256()),(owner,ref)->{
      var store=artifacts.getIfAvailable();if(store==null)throw new IllegalStateException("Artifact delivery disabled");
      var item=store.get(owner.projectId(),owner.conversationId(),ref.id());store.content(owner.projectId(),owner.conversationId(),ref.id());return item;
    },new GitPatchInspector(new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner())::capture,clock,requireAll);
  }
  public GoalCompletionGate(GoalRepository goals,ReviewReader reviews,ArtifactReader artifacts,SelfPatchReviewService.Capture capture,Clock clock,boolean requireAll){this.goals=goals;this.reviews=reviews;this.artifacts=artifacts;this.capture=capture;this.clock=clock;this.requireAll=requireAll;}
  public boolean required(GoalRepository.Goal goal){return requireAll||goal.completion()!=null;}
  public GoalRepository.Goal inspect(AgentRunContext owner,String id)throws IOException{var goal=goals.get(owner.projectId(),id);if(!owner.conversationId().equals(goal.sessionId())||!owner.projectRoot().toRealPath().toString().equals(goal.projectRoot()))throw new IllegalArgumentException("Goal outside captured owner");return goal;}
  public GoalRepository.Goal attach(AgentRunContext owner,String id,Proof proof,boolean human)throws IOException{
    validateProof(proof);if(!human&&!proof.deliveredArtifacts().isEmpty())throw new IllegalArgumentException("Human artifact handover acknowledgment required");var goal=goals.get(owner.projectId(),id);
    if(owner.mode()!=AgentRunContext.Mode.EXCLUSIVE||!owner.conversationId().equals(goal.sessionId())||!owner.projectRoot().toRealPath().toString().equals(goal.projectRoot())
        ||goal.status().equals("RUNNING")&&!Objects.equals(goal.currentRunId(),owner.runId())||!human&&!goal.status().equals("RUNNING"))throw new IllegalArgumentException("Captured owning Goal Run or stopped human Goal required");
    if(goal.completion()==null)throw new IllegalStateException("Human completion definition required before attaching evidence");
    if(proof.review()!=null)reviews.read(owner,proof.review());for(var ref:proof.artifacts()){var item=artifacts.read(owner,ref);if(!ownedArtifact(goal,item,ref))throw new IllegalArgumentException("Artifact outside Goal ownership or unavailable");}
    return goals.saveCompletionProof(owner,id,proof,human);
  }
  public FileGoalVerifier.Verification verify(GoalRepository.Goal goal,FileGoalVerifier files){
    if(!required(goal))return new FileGoalVerifier.Verification(true,"criteria_verified");
    var definition=goal.completion();if(definition==null)return failed("completion_definition_missing");
    try{
      validateDefinition(definition);var root=Path.of(goal.projectRoot());if(!root.toRealPath().equals(root))return failed("project_path_changed");
      long deadline=System.nanoTime()+Duration.ofSeconds(30).toNanos();
      for(var criterion:definition.completionEvidence()){var observed=files.verify(root,criterion);if(!observed.satisfied())return observed;}
      for(var criterion:definition.requiredPredicates()){var observed=files.verify(root,criterion);if(!observed.satisfied())return observed;}
      for(var requirement:definition.requirements())if(requirement.required()){
        var observed=files.verify(root,requirement.criterion());if(!observed.satisfied())return Set.of("digest_mismatch","json_value_mismatch","predicate_mismatch","file_missing_or_not_regular").contains(observed.reason())?failed("completion_requirement_unmet"):observed;
      }
      var proof=goal.completionProof();boolean needsReview=definition.requiredTests()!=null||definition.reviewGate()!=null;
      if(proof==null&&(needsReview||!definition.requiredArtifacts().isEmpty()))return failed("completion_evidence_missing");
      var owner=new AgentRunContext(goal.currentRunId()==null?"verify":goal.currentRunId(),goal.sessionId(),root,goal.projectId());
      SemanticPatchReviewService.Receipt receipt=null;
      if(needsReview){
        if(proof.review()==null)return failed("completion_evidence_missing");receipt=reviews.read(owner,proof.review());var detail=receipt.detail();
        if(detail==null||!detail.project().equals(goal.projectId())||!detail.root().equals(root.toString())||!detail.session().equals(goal.sessionId())||!receipt.status().equals(detail.status())
            ||!Set.of("DETERMINISTIC_CHECKS_ONLY","REVIEWED_CHECKS").contains(receipt.status())||!detail.checks().isEmpty()||detail.verification()==null||!"VERIFIED_CHECKS".equals(detail.verification().status()))return failed("completion_review_not_verified");
        if(detail.checkedAt().isBefore(clock.instant().minus(Duration.ofDays(1)))||detail.checkedAt().isAfter(clock.instant().plusSeconds(60)))return failed("completion_review_stale");
        if(definition.reviewGate()!=null){
          if(!new HashSet<>(definition.reviewGate().requirements()).equals(new HashSet<>(detail.requirements())))return failed("completion_requirement_mismatch");
          if(definition.reviewGate().semanticRequired()&&(!receipt.status().equals("REVIEWED_CHECKS")||detail.semantic()==null||!"MATCH".equals(detail.semantic().status())))return failed("completion_review_not_verified");
        }
        var verified=detail.verification();if(!verified.patchVersion().equals(verified.currentVersion()))return failed("completion_review_stale");
        var snapshot=capture.capture(root,deadline);if(!snapshot.complete()||!snapshot.version().equals(verified.patchVersion()))return failed("completion_review_stale");
        var observedTests=new HashSet<String>();
        for(var fact:detail.tests()){
          checkDeadline(deadline);var report=new TestReportDiagnosisService().read(root,fact.path());
          if(!fact.complete()||!report.sha256().equals(fact.sha256())||report.partial()||report.observed().failures()!=0||report.observed().errors()!=0||report.observed().skipped()!=0)return failed("completion_test_evidence_changed");
          var passed=report.testCases().stream().filter(test->test.outcome().equals("PASSED")).map(TestReportDiagnosisService.TestCase::test).collect(java.util.stream.Collectors.toSet());
          if(!passed.containsAll(fact.passedTests()))return failed("completion_test_evidence_changed");observedTests.addAll(fact.passedTests());
        }
        if(definition.requiredTests()!=null&&(!definition.requiredTests().commandSha256().equals(detail.commandSha256())||!observedTests.containsAll(definition.requiredTests().tests())))return failed("completion_required_tests_missing");
      }
      var available=new ArrayList<Artifact>();if(proof!=null)for(var ref:proof.artifacts()){checkDeadline(deadline);var item=artifacts.read(owner,ref);if(!ownedArtifact(goal,item,ref))return failed("completion_artifact_unavailable");available.add(item);}
      for(var expected:definition.requiredArtifacts())if(available.stream().noneMatch(item->matchesArtifact(expected,item)))return failed("completion_required_artifact_missing");
      for(var expected:definition.requiredArtifacts())if(expected.deliveryRequired()&&available.stream().noneMatch(item->matchesArtifact(expected,item)&&proof.deliveredArtifacts().contains(new Reference(item.artifactId(),item.sha256()))))return failed("completion_delivery_pending");
      if(receipt!=null){var current=capture.capture(root,deadline);if(!current.complete()||!current.version().equals(receipt.detail().verification().patchVersion()))return failed("completion_review_stale");}
      checkDeadline(deadline);return new FileGoalVerifier.Verification(true,"completion_gate_verified");
    }catch(IOException|RuntimeException unavailable){RunCancellation.propagate(unavailable);return failed("completion_evidence_unavailable");}
  }
  private static boolean matchesArtifact(ArtifactRequirement expected,Artifact item){return item.filename().equals(expected.filename())&&item.mediaType().equals(expected.mediaType())&&(expected.sha256()==null||expected.sha256().equals(item.sha256()));}
  private boolean ownedArtifact(GoalRepository.Goal goal,Artifact item,Reference ref){return item!=null&&"AVAILABLE".equals(item.status())&&item.projectId().equals(goal.projectId())&&Objects.equals(item.sessionId(),goal.sessionId())&&item.sha256().equals(ref.sha256())&&item.artifactId().equals(ref.id())&&item.size()>=0&&item.expiresAt().isAfter(clock.instant());}
  private static FileGoalVerifier.Verification failed(String reason){return new FileGoalVerifier.Verification(false,reason);}
  private static void checkDeadline(long deadline)throws IOException{RunCancellation.propagate(null);if(System.nanoTime()>=deadline)throw new IOException("Completion verification deadline exceeded");}
  static void validateDefinition(Definition definition){
    if(definition==null||definition.completionEvidence().isEmpty()||definition.completionEvidence().size()>16||definition.requiredPredicates().size()>16||definition.requiredArtifacts().size()>16)throw new IllegalArgumentException("Bounded completion evidence required");
    if(definition.requirements().size()>16)throw new IllegalArgumentException("At most 16 named requirements");
    var requirementIds=new HashSet<String>();for(var item:definition.requirements()){
      if(item==null||item.id()==null||!item.id().matches("[A-Za-z0-9_.-]{1,64}")||!requirementIds.add(item.id())||item.statement()==null||item.statement().isBlank()||item.statement().length()>1024||item.statement().codePoints().anyMatch(Character::isISOControl)||!CredentialRedactor.redact(item.statement()).equals(item.statement()))throw new IllegalArgumentException("Unique bounded named requirements required");
      criterion(item.criterion());if(!item.criterion().jsonCriterion()&&(item.criterion().sha256()==null||!item.criterion().sha256().matches("[a-f0-9]{64}")))throw new IllegalArgumentException("Requirement needs exact SHA or existing JSON predicate");
    }
    for(var item:definition.completionEvidence()){criterion(item);if(item.jsonCriterion()||item.sha256()==null||!item.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Completion evidence needs exact SHA");}
    for(var item:definition.requiredPredicates()){criterion(item);if(!item.jsonCriterion())throw new IllegalArgumentException("Required predicate must be declarative/scalar JSON");}
    var names=new HashSet<String>();for(var artifact:definition.requiredArtifacts())if(artifact==null||artifact.filename()==null||!names.add(artifact.filename())||artifact.filename().isBlank()||artifact.filename().length()>128||artifact.filename().codePoints().anyMatch(Character::isISOControl)||artifact.filename().matches(".*[/\\\\:].*")||!Set.of("text/plain","text/markdown","application/json","application/pdf","image/png","image/jpeg","application/octet-stream").contains(artifact.mediaType())||artifact.sha256()!=null&&!artifact.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid required Artifact");
    var tests=definition.requiredTests();if(tests!=null&&(tests.commandSha256()==null||!tests.commandSha256().matches("[a-f0-9]{64}")||tests.tests().isEmpty()||tests.tests().size()>64||new HashSet<>(tests.tests()).size()!=tests.tests().size()||tests.tests().stream().anyMatch(test->test==null||test.isBlank()||test.length()>256||test.codePoints().anyMatch(Character::isISOControl)||!CredentialRedactor.redact(test).equals(test))))throw new IllegalArgumentException("Required command SHA and bounded unique testcase IDs required");
    if(tests!=null&&tests.testCommand()!=null&&(tests.testCommand().isBlank()||tests.testCommand().length()>4096||tests.testCommand().codePoints().anyMatch(Character::isISOControl)||!CredentialRedactor.redact(tests.testCommand()).equals(tests.testCommand())||!commandHash(tests.testCommand()).equals(tests.commandSha256())))throw new IllegalArgumentException("Explicit test command and SHA must match");
    var review=definition.reviewGate();if(review!=null){if(review.requirements().isEmpty()||review.requirements().size()>16)throw new IllegalArgumentException("Review requirements required");var ids=new HashSet<String>();int total=0;for(var requirement:review.requirements()){if(requirement==null||requirement.id()==null||!requirement.id().matches("[A-Za-z0-9_.-]{1,64}")||!ids.add(requirement.id())||requirement.statement()==null||requirement.statement().isBlank()||requirement.statement().length()>1024||!CredentialRedactor.redact(requirement.statement()).equals(requirement.statement())||requirement.files().isEmpty()||requirement.tests().isEmpty()||(total+=requirement.files().size()+requirement.tests().size())>192)throw new IllegalArgumentException("Bounded unique review requirements required");requirement.files().forEach(GoalRepository::validateFile);for(var test:requirement.tests())if(test==null||test.isBlank()||test.length()>256||!CredentialRedactor.redact(test).equals(test))throw new IllegalArgumentException("Invalid required testcase");}}
  }
  private static void criterion(GoalRepository.FileCriterion item){if(item==null)throw new IllegalArgumentException("File criterion required");GoalRepository.validateFile(item.relativeFile());if(item.predicateJson()!=null){if(item.jsonPointer()!=null||item.expectedJson()!=null||item.sha256()!=null&&!item.sha256().isEmpty())throw new IllegalArgumentException("One predicate type required");dev.mikoto2000.rei.core.predicate.DeclarativePredicate.parse(item.predicateJson());}else if(item.jsonCriterion()){if(item.sha256()!=null&&!item.sha256().isEmpty())throw new IllegalArgumentException("JSON predicate cannot include digest");JsonFileGoalCondition.parse(item.jsonPointer(),item.expectedJson());}}
  static void validateProof(Proof proof){if(proof==null||proof.artifacts().size()>16||proof.deliveredArtifacts().size()>16)throw new IllegalArgumentException("Bounded proof required");var ids=new HashSet<String>();if(proof.review()!=null)reference(proof.review());for(var ref:proof.artifacts()){reference(ref);if(!ids.add(ref.id()))throw new IllegalArgumentException("Duplicate Artifact proof");}var delivered=new HashSet<Reference>();for(var ref:proof.deliveredArtifacts()){reference(ref);if(!proof.artifacts().contains(ref)||!delivered.add(ref))throw new IllegalArgumentException("Delivery must reference a unique attached exact Artifact");}}
  private static void reference(Reference ref){if(ref==null||ref.id()==null||!ref.id().matches("[a-fA-F0-9-]{36}")||ref.sha256()==null||!ref.sha256().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact evidence ID/SHA required");}
  private static String commandHash(String command){try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(command.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
