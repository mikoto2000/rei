package dev.mikoto2000.rei.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.CredentialRedactor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Requirement traceability around the existing test/static review cycle; model agreement never replaces checks. */
@Service
public final class SemanticPatchReviewService {
  public record Requirement(String id,String statement,List<String> files,List<String> tests){public Requirement{files=files==null?List.of():List.copyOf(files);tests=tests==null?List.of():List.copyOf(tests);}}
  public record Request(String testCommand,Integer timeoutSeconds,List<Requirement> requirements,List<String> allowedFiles,List<String> testReports,boolean semantic){public Request{requirements=requirements==null?List.of():List.copyOf(requirements);allowedFiles=allowedFiles==null?List.of():List.copyOf(allowedFiles);testReports=testReports==null?List.of():List.copyOf(testReports);}}
  public record FileFact(String path,String sha256,boolean exists) {}
  public record TestFact(String path,String sha256,Instant modifiedAt,List<String> passedTests,TestReportDiagnosisService.Counts counts,boolean complete){public TestFact{passedTests=List.copyOf(passedTests);}}
  public record Check(String code,String requirementId,String path) {}
  public record Verdict(String status,Map<String,String> requirements,Map<String,String> dimensions){public Verdict{requirements=Map.copyOf(requirements);dimensions=Map.copyOf(dimensions);}}
  public record Input(String originalTask,List<Requirement> requirements,String patchVersion,String diff,Map<String,String> sources,List<TestFact> tests) {}
  public record Detail(String project,String root,String session,String run,String status,Instant checkedAt,String commandSha256,
      List<Requirement> requirements,List<FileFact> files,List<TestFact> tests,List<Check> checks,SelfPatchReviewService.Result verification,
      Verdict semantic,boolean truthVerified,List<String> warnings) {}
  public record Receipt(String id,String status,String sha256,Detail detail) {}
  public record Inspection(String id,String status,String sha256,boolean completed,long pid,Instant heartbeat,List<String> warnings){}
  @FunctionalInterface public interface MaterialReader{GitPatchInspector.Material read(Path root,SelfPatchReviewService.Snapshot snapshot,long deadline)throws IOException;}
  @FunctionalInterface public interface SemanticReviewer{Verdict judge(Input input,RunExecutionContext run,long deadline)throws IOException;}
  static final Set<String> DIMENSIONS=Set.of("requirements","extraChanges","security","tests","hygiene","compatibility");
  private final JdbcClient db;private final Clock clock;private final boolean semanticEnabled;
  private final SelfPatchReviewService.Capture capture;private final SelfPatchRepairService.Cycle cycle;private final MaterialReader materials;private final SemanticReviewer reviewer;
  private final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  private final PersistedReceiptLease leases;
  @org.springframework.beans.factory.annotation.Autowired
  public SemanticPatchReviewService(@org.springframework.beans.factory.annotation.Qualifier("memoryConsolidationDataSource") DataSource source,
      Clock clock,@org.springframework.beans.factory.annotation.Value("${rei.patch-review.semantic-enabled:false}") boolean semanticEnabled,
      dev.mikoto2000.rei.core.service.SystemShellService shell,dev.mikoto2000.rei.llm.LlmModelProvider models){
    this(source,clock,semanticEnabled,new GitPatchInspector(new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner())::capture,
        new SelfPatchReviewService(shell)::verify,new GitPatchInspector(new dev.mikoto2000.rei.externalagent.ExternalAgentProcessRunner())::material,new PatchSemanticReviewer(models::subAgentChatModel)::judge);
  }
  public SemanticPatchReviewService(DataSource source,Clock clock,boolean semanticEnabled,SelfPatchReviewService.Capture capture,
      SelfPatchRepairService.Cycle cycle,MaterialReader materials,SemanticReviewer reviewer){
    this.clock=clock;this.semanticEnabled=semanticEnabled;this.capture=capture;this.cycle=cycle;this.materials=materials;this.reviewer=reviewer;db=JdbcClient.create(source);
    db.sql("CREATE TABLE IF NOT EXISTS patch_requirement_reviews(id TEXT PRIMARY KEY,project TEXT NOT NULL,root TEXT NOT NULL,session TEXT NOT NULL,run TEXT NOT NULL,request_hash TEXT NOT NULL,status TEXT NOT NULL,payload TEXT,sha TEXT,UNIQUE(project,root,session,run,request_hash))").update();
    leases=new PersistedReceiptLease(source,"patch_requirement_reviews",clock);
  }
  public Receipt review(AgentRunContext owner,Request request,RunExecutionContext run)throws IOException {
    Path root=owner(owner);if(owner.mode()!=AgentRunContext.Mode.EXCLUSIVE)throw new IllegalArgumentException("Exclusive Run required for explicit test execution");validate(request);
    long deadline=System.nanoTime()+Duration.ofSeconds(180).toNanos();String requestHash=hash(encode(request));
    var previous=db.sql("SELECT id,status,payload,sha FROM patch_requirement_reviews WHERE project=? AND root=? AND session=? AND run=? AND request_hash=?").params(owner.projectId(),root.toString(),owner.conversationId(),owner.runId(),requestHash).query((row,n)->stored(row.getString("id"),row.getString("status"),row.getString("payload"),row.getString("sha"))).optional();
    if(previous.isPresent())return previous.get();
    var snapshot=capture.capture(root,deadline);String id=UUID.randomUUID().toString();
    int claimed=db.sql("INSERT OR IGNORE INTO patch_requirement_reviews(id,project,root,session,run,request_hash,status,payload,sha) SELECT ?,?,?,?,?,?,'STARTED',NULL,NULL WHERE (SELECT count(*) FROM patch_requirement_reviews WHERE project=?)<128 AND (SELECT count(*) FROM patch_requirement_reviews)<4096")
        .params(id,owner.projectId(),root.toString(),owner.conversationId(),owner.runId(),requestHash,owner.projectId()).update();
    if(claimed!=1)throw new IllegalStateException("Review already claimed or history capacity reached; inspect without replay");
    var checks=new ArrayList<Check>();var files=new ArrayList<FileFact>();var tests=new ArrayList<TestFact>();SelfPatchReviewService.Result verification=null;Verdict verdict=null;String status="CHECKS_INCOMPLETE";Instant started=clock.instant();
    try(var lease=leases.activate(id)) {
      if(!snapshot.complete()||snapshot.changedFiles().isEmpty()||snapshot.changedFiles().size()>32)checks.add(new Check("PATCH_INCOMPLETE","",""));
      else {
        for(String path:snapshot.changedFiles())if(!request.allowedFiles().contains(path))checks.add(new Check("EXTRA_CHANGE","",path));
        for(String path:snapshot.changedFiles())if(request.requirements().stream().noneMatch(requirement->requirement.files().contains(path)))checks.add(new Check("UNASSIGNED_CHANGE","",path));
        if(checks.isEmpty()){
          verification=cycle.verify(root,new SelfPatchReviewService.Request(request.testCommand(),request.timeoutSeconds()),deadline);
          if(!"VERIFIED_CHECKS".equals(verification.status())||!snapshot.version().equals(verification.patchVersion())||!snapshot.version().equals(verification.currentVersion()))checks.add(new Check("TEST_STATIC_CHECKS_NOT_VERIFIED","",""));
          else {
            var material=materials.read(root,snapshot,deadline);validateMaterial(snapshot,material);
            for(String path:snapshot.changedFiles()){String text=material.sources().get(path);files.add(new FileFact(path,text==null?hash("DELETED"):hash(text),text!=null));}
            hygiene(material.diff(),snapshot.changedFiles(),checks);
            var needed=new TreeSet<String>();request.requirements().forEach(requirement->needed.addAll(requirement.tests()));
            for(String path:request.testReports()) {
              SelfPatchReviewService.remaining(deadline,1);
              try {
                var report=new TestReportDiagnosisService().read(root,path);
                boolean fresh=!report.modifiedAt().isBefore(started.minusSeconds(1))&&!report.modifiedAt().isAfter(clock.instant().plusSeconds(60));
                boolean complete=!report.partial()&&report.reported()!=null&&report.observed().tests()>0&&report.observed().failures()==0&&report.observed().errors()==0&&report.observed().skipped()==0&&fresh;
                var passed=report.testCases().stream().filter(item->item.outcome().equals("PASSED")&&needed.contains(item.test())).map(TestReportDiagnosisService.TestCase::test).distinct().sorted().toList();
                tests.add(new TestFact(report.path(),report.sha256(),report.modifiedAt(),passed,report.observed(),complete));
                if(!complete)checks.add(new Check("TEST_REPORT_INCOMPLETE_OR_NOT_CURRENT","",path));
              }catch(IOException invalid){checks.add(new Check("TEST_REPORT_UNAVAILABLE","",path));}
            }
            var passed=new HashSet<String>();tests.stream().filter(TestFact::complete).forEach(test->passed.addAll(test.passedTests()));
            for(var requirement:request.requirements()){
              for(String path:requirement.files())if(files.stream().noneMatch(file->file.path().equals(path)))checks.add(new Check("REQUIREMENT_FILE_EVIDENCE_MISSING",requirement.id(),path));
              for(String test:requirement.tests())if(!passed.contains(test))checks.add(new Check("REQUIRED_TEST_NOT_OBSERVED_PASSED",requirement.id(),test));
            }
            var current=capture.capture(root,deadline);if(!current.complete()||!snapshot.version().equals(current.version()))checks.add(new Check("PATCH_CHANGED","",""));
            if(checks.isEmpty()){
              status="DETERMINISTIC_CHECKS_ONLY";
              if(request.semantic()){
                if(!semanticEnabled||run==null||!owner.equals(run.runContext())||run.sharedLlmReservation()==null||run.sharedLlmReservation().remaining()<1)status="SEMANTIC_UNAVAILABLE";
                else {
                  run.checkActive();var safeSources=new TreeMap<String,String>();material.sources().forEach((path,text)->safeSources.put(path,CredentialRedactor.redact(text)));
                  verdict=reviewer.judge(new Input(CredentialRedactor.redact(run.userRequest()),request.requirements(),snapshot.version(),CredentialRedactor.redact(material.diff()),Map.copyOf(safeSources),List.copyOf(tests)),run,deadline);
                  if(valid(verdict,request))status=verdict.status().equals("MATCH")?"REVIEWED_CHECKS":verdict.status().equals("FAIL")?"FIX_REQUIRED":"SEMANTIC_UNKNOWN";else {status="SEMANTIC_UNKNOWN";var requirementUnknown=new TreeMap<String,String>();request.requirements().forEach(item->requirementUnknown.put(item.id(),"UNKNOWN"));var dimensionsUnknown=new TreeMap<String,String>();DIMENSIONS.forEach(item->dimensionsUnknown.put(item,"UNKNOWN"));verdict=new Verdict("UNKNOWN",requirementUnknown,dimensionsUnknown);}
                }
              }
              current=capture.capture(root,deadline);if(!current.complete()||!snapshot.version().equals(current.version())){checks.add(new Check("PATCH_CHANGED","",""));status="FIX_REQUIRED";}
            }
          }
        }
      }
      if(!checks.isEmpty())status="FIX_REQUIRED";
      SelfPatchReviewService.remaining(deadline,1);
      var detail=new Detail(owner.projectId(),root.toString(),owner.conversationId(),owner.runId(),status,clock.instant(),hash(request.testCommand()),request.requirements(),List.copyOf(files),List.copyOf(tests),checks.stream().limit(64).toList(),verification,verdict,false,
          List.of("Requirement traceability and explicit command/static observations only; no universal semantic correctness or coverage proof","Saved reports may not independently establish command-to-report provenance; semantic judgement is probabilistic"));
      String payload=encode(detail),sha=hash(payload);
      leases.heartbeat(id);
      if(db.sql("UPDATE patch_requirement_reviews SET status=?,payload=?,sha=? WHERE id=? AND status='STARTED'").params(status,payload,sha,id).update()!=1)throw new IOException("Review receipt unavailable");
      return new Receipt(id,status,sha,detail);
    }catch(IOException|RuntimeException error){try{db.sql("UPDATE patch_requirement_reviews SET status='UNKNOWN' WHERE id=? AND status='STARTED'").param(id).update();}catch(RuntimeException persistence){error.addSuppressed(persistence);}RunCancellation.propagate(error);throw error;}
  }
  public Receipt get(AgentRunContext owner,String id,String sha)throws IOException {
    Path root=owner(owner);if(id==null||!id.matches("[0-9a-fA-F-]{36}")||sha==null||!sha.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Exact review ID/SHA required");
    var receipt=db.sql("SELECT id,status,payload,sha FROM patch_requirement_reviews WHERE id=? AND project=? AND root=? AND session=?").params(id,owner.projectId(),root.toString(),owner.conversationId()).query((row,n)->stored(row.getString("id"),row.getString("status"),row.getString("payload"),row.getString("sha"))).optional().orElseThrow(()->new IllegalArgumentException("Review not found in this Project/root/session"));
    if(!receipt.sha256().equals(sha))throw new IllegalArgumentException("Review SHA mismatch");return receipt;
  }
  public Inspection inspect(AgentRunContext owner,String id)throws IOException{Path root=owner(owner);if(id==null||!id.matches("[0-9a-fA-F-]{36}"))throw new IllegalArgumentException("Review UUID required");if(db.sql("SELECT count(*) FROM patch_requirement_reviews WHERE id=? AND project=? AND root=? AND session=?").params(id,owner.projectId(),root.toString(),owner.conversationId()).query(Integer.class).single()!=1)throw new IllegalArgumentException("Review not found in this Project/root/session");leases.reconcile(id);return db.sql("SELECT id,status,sha,payload,pid,heartbeat FROM patch_requirement_reviews WHERE id=? AND project=? AND root=? AND session=?").params(id,owner.projectId(),root.toString(),owner.conversationId()).query((rs,n)->new Inspection(rs.getString("id"),rs.getString("status"),rs.getString("sha"),rs.getString("payload")!=null&&rs.getString("sha")!=null,rs.getLong("pid"),rs.getObject("heartbeat")==null?null:Instant.ofEpochMilli(rs.getLong("heartbeat")),List.of("Historical execution state only; no automatic replay or correctness proof"))).optional().orElseThrow(()->new IllegalArgumentException("Review not found in this Project/root/session"));}
  public List<Inspection> list(AgentRunContext owner)throws IOException{Path root=owner(owner);var ids=db.sql("SELECT id FROM patch_requirement_reviews WHERE project=? AND root=? AND session=? ORDER BY rowid DESC LIMIT 128").params(owner.projectId(),root.toString(),owner.conversationId()).query(String.class).list();var results=new ArrayList<Inspection>();for(String id:ids)results.add(inspect(owner,id));return List.copyOf(results);}
  private Receipt stored(String id,String status,String payload,String sha){if(payload==null||sha==null)throw new IllegalStateException("Review STARTED/UNKNOWN; inspect Run outcomes without automatic replay");if(!hash(payload).equals(sha))throw new IllegalStateException("Review receipt changed");var detail=decode(payload,Detail.class);if(!status.equals(detail.status())||detail.truthVerified())throw new IllegalStateException("Invalid review receipt");return new Receipt(id,status,sha,detail);}
  private static boolean valid(Verdict verdict,Request request){if(verdict==null||!Set.of("MATCH","FAIL","UNKNOWN").contains(verdict.status())||!verdict.dimensions().keySet().equals(DIMENSIONS)||!verdict.requirements().keySet().equals(request.requirements().stream().map(Requirement::id).collect(java.util.stream.Collectors.toSet())))return false;var values=new ArrayList<>(verdict.dimensions().values());values.addAll(verdict.requirements().values());if(values.stream().anyMatch(value->!Set.of("PASS","FAIL","UNKNOWN").contains(value)))return false;return switch(verdict.status()){case "MATCH"->values.stream().allMatch("PASS"::equals);case "FAIL"->values.contains("FAIL");default->values.contains("UNKNOWN")&&!values.contains("FAIL");};}
  private static void hygiene(String diff,List<String> changed,List<Check> checks){String path=null;boolean inHunk=false;var added=new StringBuilder();
    for(String line:diff.split("\n",-1)){if(line.startsWith("diff --git ")){scanAdded(path,added.toString(),checks);added.setLength(0);path=null;inHunk=false;}else if(!inHunk&&line.startsWith("+++ ")){String name=line.startsWith("+++ b/")?line.substring(6):null;if(name!=null&&changed.contains(name))path=name;else if(!line.equals("+++ /dev/null"))checks.add(new Check("DIFF_PATH_UNMAPPED","",""));}else if(line.startsWith("@@"))inHunk=true;else if(inHunk&&path!=null&&line.startsWith("+"))added.append(line.substring(1)).append('\n');}scanAdded(path,added.toString(),checks);}
  private static void scanAdded(String path,String text,List<Check> checks){if(path==null||!path.matches(".*\\.(java|ts|tsx|js|jsx|rs|go|py)$"))return;
    var lexical=HeuristicSourceIndex.lex(text,HeuristicSourceIndex.language(path));if(!lexical.valid()){checks.add(new Check("HYGIENE_LEXICAL_INPUT_INCOMPLETE","",path));return;}
    if(com.google.re2j.Pattern.compile("\\b(?:TODO|FIXME)\\b").matcher(lexical.comments()).find())checks.add(new Check("UNFINISHED_IMPLEMENTATION","",path));
    if(com.google.re2j.Pattern.compile("@(?:Disabled|Ignore)\\b|\\b(?:it|test|describe)\\.(?:skip|only)\\s*\\(|#\\[ignore\\]|pytest\\.mark\\.(?:skip|xfail)").matcher(lexical.masked()).find())checks.add(new Check("DISABLED_OR_FOCUSED_TEST","",path));
    if(com.google.re2j.Pattern.compile("(?s)catch\\s*\\([^)]{1,512}\\)\\s*\\{\\s*\\}").matcher(lexical.masked()).find())checks.add(new Check("SWALLOWED_EXCEPTION","",path));
    if(com.google.re2j.Pattern.compile("permitAll\\s*\\(|verify\\s*=\\s*False|rejectUnauthorized\\s*:\\s*false").matcher(lexical.masked()).find()||lexical.tokens().stream().anyMatch(token->token.literal()&&token.value().equals("--no-sandbox")))checks.add(new Check("SECURITY_BOUNDARY_REVIEW_REQUIRED","",path));
  }
  private static void validateMaterial(SelfPatchReviewService.Snapshot snapshot,GitPatchInspector.Material material)throws IOException {if(material==null||material.diff()==null||material.diff().getBytes(StandardCharsets.UTF_8).length>32768||material.sources().size()>32||!snapshot.changedFiles().containsAll(material.sources().keySet()))throw new IOException("Review material invalid");int total=0;for(String text:material.sources().values())if(text==null||text.getBytes(StandardCharsets.UTF_8).length>65536||(total+=text.getBytes(StandardCharsets.UTF_8).length)>65536)throw new IOException("Review source budget exceeded");}
  private static void validate(Request request){if(request==null||request.testCommand()==null||request.testCommand().isBlank()||request.testCommand().length()>4096||!CredentialRedactor.redact(request.testCommand()).equals(request.testCommand())||request.testCommand().codePoints().anyMatch(Character::isISOControl)||request.timeoutSeconds()!=null&&(request.timeoutSeconds()<1||request.timeoutSeconds()>60)||request.requirements().isEmpty()||request.requirements().size()>16||request.allowedFiles().isEmpty()||request.allowedFiles().size()>32||request.testReports().isEmpty()||request.testReports().size()>8)throw new IllegalArgumentException("Bounded requirements, changed files, test reports and explicit command required");
    uniquePaths(request.allowedFiles());uniquePaths(request.testReports());var ids=new HashSet<String>();int tests=0,files=0;
    for(var requirement:request.requirements()){if(requirement==null||requirement.id()==null||!requirement.id().matches("[A-Za-z0-9_.-]{1,64}")||!ids.add(requirement.id())||requirement.statement()==null||requirement.statement().isBlank()||requirement.statement().length()>1024||!CredentialRedactor.redact(requirement.statement()).equals(requirement.statement())||requirement.files().isEmpty()||requirement.tests().isEmpty()||(files+=requirement.files().size())>128||(tests+=requirement.tests().size())>64)throw new IllegalArgumentException("Each unique requirement needs bounded source and testcase evidence");uniquePaths(requirement.files());if(new HashSet<>(requirement.tests()).size()!=requirement.tests().size()||requirement.tests().stream().anyMatch(test->test==null||test.isBlank()||test.length()>256||test.codePoints().anyMatch(Character::isISOControl)||!CredentialRedactor.redact(test).equals(test)))throw new IllegalArgumentException("Invalid required testcase identity");}
  }
  private static void uniquePaths(List<String> paths){if(new HashSet<>(paths).size()!=paths.size())throw new IllegalArgumentException("Unique paths required");for(String name:paths){if(name==null||name.isBlank()||name.length()>1024||name.contains(":")||name.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Project-relative path required");Path path=Path.of(name);if(path.isAbsolute()||path.getRoot()!=null||path.normalize().startsWith("..")||!path.normalize().toString().replace('\\','/').equals(name))throw new IllegalArgumentException("Normalized project-relative path required");}}
  private static Path owner(AgentRunContext owner)throws IOException {RunCancellation.propagate(null);if(owner==null||owner.projectId()==null||owner.conversationId().startsWith("subagent:"))throw new IllegalArgumentException("Current human Project/session required");return owner.projectRoot().toRealPath();}
  private String encode(Object value){try{String payload=json.writeValueAsString(value);if(payload.getBytes(StandardCharsets.UTF_8).length>131072)throw new IllegalArgumentException("Review receipt exceeds 128KiB");return payload;}catch(IOException invalid){throw new IllegalStateException("Review serialization unavailable",invalid);}}
  private <T>T decode(String value,Class<T> type){try{if(value.length()>131072)throw new IllegalStateException("Review receipt exceeds bounds");return json.readValue(value,type);}catch(IOException invalid){throw new IllegalStateException("Review receipt unavailable",invalid);}}
  private static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
