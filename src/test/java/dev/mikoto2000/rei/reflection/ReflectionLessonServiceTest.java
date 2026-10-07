package dev.mikoto2000.rei.reflection;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.*;
import dev.mikoto2000.rei.goal.*;
import static org.junit.jupiter.api.Assertions.*;

class ReflectionLessonServiceTest {
  @TempDir Path root;
  Clock clock=Clock.fixed(Instant.parse("2026-10-07T09:00:00Z"),ZoneOffset.UTC);
  DriverManagerDataSource data;RunReflectionRepository runs;GoalReflectionRepository reflections;GoalRepository goals;
  AgentRunContext owner;ReflectionLessonService service;
  @BeforeEach void setup(){data=new DriverManagerDataSource("jdbc:sqlite:"+root.resolve("lessons.db"));runs=new RunReflectionRepository(data,clock);reflections=new GoalReflectionRepository(data,clock);goals=new GoalRepository(data,clock);owner=new AgentRunContext("human","session",root,"project");service=create(clock,true);}
  ReflectionLessonService create(Clock current,boolean enabled){return new ReflectionLessonService(data,runs,reflections,goals,new FileGoalVerifier(),current,enabled);}
  ReflectionLessonService.Observation failure(String run) {
    var events=new AgentEventFactory(clock);var source=new AgentRunContext(run,"session",root,"project");
    var item=runs.reflect(events.runFailed(run,new ErrorInformation("MissingCapability","token=private",null)).withOwnership(source),"RUN",run,"FAILED","MissingCapability");
    return service.observe(owner,"RUN",item.id());
  }
  ReflectionLessonService.Lesson candidate(int count) {
    var evidence=java.util.stream.IntStream.range(0,count).mapToObj(i->failure("run-"+i).id()).toList();
    return service.propose(owner,ReflectionLessonService.Kind.FAILURE_PATTERN,"Check the required capability before retrying this class of task.",evidence);
  }
  @Test void repeatedObservedFailuresBecomeCandidateButNeverAutomaticMemory() {
    var lesson=candidate(3);assertEquals("CANDIDATE_LESSON",lesson.state());assertEquals(3,lesson.occurrenceCount());assertEquals(3,lesson.evidence().size());
    assertNotNull(lesson.evidenceHash());assertFalse(lesson.toString().contains("private"));
    var validated=service.validate(owner,lesson.id(),lesson.revision(),lesson.evidenceHash(),"Reviewed all recorded failures and searched for counterexamples; no universal guarantee.");
    assertEquals("VALIDATED_LESSON",validated.state());assertEquals(lesson.statement(),validated.statement());
    assertEquals(validated,create(clock,true).get(owner,lesson.id()));
  }
  @Test void countFreshnessRevisionAndCounterexampleReviewGateValidation() {
    var insufficient=candidate(1);assertThrows(IllegalArgumentException.class,()->service.validate(owner,insufficient.id(),0,insufficient.evidenceHash(),"reviewed"));
    var lesson=candidate(3);
    assertThrows(IllegalArgumentException.class,()->service.validate(owner,lesson.id(),0,"f".repeat(64),"reviewed"));
    assertThrows(IllegalArgumentException.class,()->service.validate(owner,lesson.id(),0,lesson.evidenceHash(),""));
    assertThrows(IllegalArgumentException.class,()->create(Clock.offset(clock,Duration.ofDays(31)),true).validate(owner,lesson.id(),0,lesson.evidenceHash(),"reviewed"));
    var validated=service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"counterexamples reviewed");
    assertThrows(IllegalArgumentException.class,()->service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"stale revision"));assertEquals(1,validated.revision());
  }
  @Test void actualCounterexampleAndUserCorrectionInvalidateValidatedLesson() {
    var lesson=candidate(3);var validated=service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"reviewed");
    var example=failure("counterexample-run");
    var rejected=service.counterexample(owner,validated.id(),validated.revision(),example.id(),"This scope also fails after the suggested check.");assertEquals("REJECTED",rejected.state());assertEquals(1,rejected.counterexamples().size());
    var corrected=service.correct(owner,rejected.id(),rejected.revision(),"The lesson scope is too broad; keep the operation-specific evidence.");assertEquals(1,corrected.corrections().size());assertEquals("REJECTED",corrected.state());
  }
  @Test void forgottenStatementCannotRegenerateWithNewEvidenceOrAfterRestart() {
    var lesson=candidate(3);service.forget(owner,lesson.id(),lesson.revision());
    var another=failure("new-run");var restored=create(clock,true);
    assertThrows(IllegalArgumentException.class,()->restored.propose(owner,ReflectionLessonService.Kind.FAILURE_PATTERN,"  "+lesson.statement().toUpperCase(Locale.ROOT)+"  ",List.of(another.id())));
    assertEquals("REJECTED",restored.get(owner,lesson.id()).state());
  }
  @Test void sourceObservationDeduplicatesAndOtherOwnerOrDisabledFeatureCannotMutate() {
    var first=failure("same-run");var again=failure("same-run");assertEquals(first.id(),again.id());assertEquals("OBSERVATION",first.state());
    var lesson=candidate(3);var other=new AgentRunContext("other","other-session",root,"project");
    assertThrows(IllegalArgumentException.class,()->service.get(other,lesson.id()));assertThrows(IllegalArgumentException.class,()->create(clock,false).propose(owner,ReflectionLessonService.Kind.FAILURE_PATTERN,"new",List.of(first.id())));
    assertThrows(IllegalArgumentException.class,()->service.propose(owner,ReflectionLessonService.Kind.FAILURE_PATTERN,"new",List.of(first.id(),first.id())));
  }
  @Test void reportedRunCompletionCannotSupportSuccessfulStrategyWithoutIndependentProof() {
    var events=new AgentEventFactory(clock);var refs=new ArrayList<String>();
    for(int i=0;i<3;i++){String run="reported-"+i;var item=runs.reflect(events.runCompleted(run,1).withOwnership(new AgentRunContext(run,"session",root,"project")),"RUN",run,"COMPLETED","");refs.add(service.observe(owner,"RUN",item.id()).id());}
    var lesson=service.propose(owner,ReflectionLessonService.Kind.SUCCESSFUL_STRATEGY,"Use the reported strategy.",refs);
    assertThrows(IllegalArgumentException.class,()->service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"reviewed"));
  }
  @Test void repeatedCorrectionsRequireDistinctHumanSourcesAndRemainScoped() {
    var refs=new ArrayList<String>();
    for(int i=0;i<3;i++)refs.add(service.observeCorrection(owner,"correction-"+i,failure("corrected-run-"+i).id(),"The check must precede the operation.").id());
    var lesson=service.propose(owner,ReflectionLessonService.Kind.REPEATED_CORRECTION,"Apply the reviewed ordering correction within this task scope.",refs);
    assertEquals("VALIDATED_LESSON",service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"Checked the recorded user corrections and scope.").state());
    assertEquals(refs.getFirst(),service.observeCorrection(owner,"correction-0",failure("corrected-run-0").id(),"The check must precede the operation.").id());
    assertThrows(IllegalArgumentException.class,()->service.observeCorrection(owner,"correction-0",failure("corrected-run-0").id(),"A different correction cannot replace the original source."));
  }
  @Test void independentlyVerifiedGoalsSupportScopedStrategyButChangedFilesStopPromotion() throws Exception {
    var collector=new GoalReflectionService(goals,reflections,new InMemoryAgentEventBus(),clock);var refs=new ArrayList<String>();
    for(int i=0;i<3;i++){String file="result-"+i+".txt";java.nio.file.Files.writeString(root.resolve(file),"result");String digest=new FileGoalVerifier().fingerprint(root.toRealPath(),file).sha256();var goal=goals.create(owner,"objective",file,digest,1,1);goals.verifiedWithoutRun("project",goal.id());refs.add(service.observe(owner,"GOAL",collector.collect("project",goal.id()).id()).id());}
    var lesson=service.propose(owner,ReflectionLessonService.Kind.SUCCESSFUL_STRATEGY,"Review the verified strategy within this scope.",refs);
    java.nio.file.Files.writeString(root.resolve("result-0.txt"),"changed");assertThrows(IllegalArgumentException.class,()->service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"reviewed"));
    java.nio.file.Files.writeString(root.resolve("result-0.txt"),"result");assertEquals("VALIDATED_LESSON",service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"reviewed").state());
  }
  @Test void shellProvidesHumanLessonOperationsAndDisabledServiceFailsClosed() {
    var projects=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectService.class);
    var project=org.mockito.Mockito.mock(dev.mikoto2000.rei.core.project.ProjectContext.class);org.mockito.Mockito.when(project.id()).thenReturn("project");org.mockito.Mockito.when(project.root()).thenReturn(root);org.mockito.Mockito.when(projects.currentContext()).thenReturn(project);org.mockito.Mockito.when(projects.currentSessionId()).thenReturn("session");
    var command=new ReflectionCommand(reflections,null,projects);command.setLessons(service);var text=new java.io.StringWriter();command.setShellOutput(new java.io.PrintWriter(text));var cli=new picocli.CommandLine(command);var lesson=candidate(3);
    assertEquals(0,cli.execute("lesson-validate",lesson.id(),"--revision","0","--evidence-hash",lesson.evidenceHash(),"--note","Reviewed counterexamples and scope."));assertTrue(text.toString().contains("VALIDATED_LESSON"));
    assertEquals(2,cli.execute("lesson-forget",lesson.id(),"--revision","0"));assertEquals(0,cli.execute("lesson-forget",lesson.id(),"--revision","1"));
    command.setLessons(null);assertEquals(2,cli.execute("lessons"));
  }
  @Test void forgettingAlsoBlocksAlreadyProposedEquivalentCandidate() {
    var first=candidate(3);var equivalent=candidate(3);service.forget(owner,first.id(),0);
    assertThrows(IllegalArgumentException.class,()->service.validate(owner,equivalent.id(),0,equivalent.evidenceHash(),"reviewed"));
  }
  @Test void cancellationCannotPromoteOrRecordHumanEvidence() {
    var lesson=candidate(3);
    try {Thread.currentThread().interrupt();assertThrows(IllegalArgumentException.class,()->service.validate(owner,lesson.id(),0,lesson.evidenceHash(),"reviewed"));}
    finally {Thread.interrupted();}
    assertEquals("CANDIDATE_LESSON",service.get(owner,lesson.id()).state());
  }
}
