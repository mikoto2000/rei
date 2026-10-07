package dev.mikoto2000.rei.subagent;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SubAgentConsensusServiceTest {
  @TempDir Path temporary;
  DurableSubAgentRepository repository;SubAgentRunner runner;SubAgentRegistry registry;SubAgentProperties properties;
  AgentRunContext owner;SubAgentConsensusService service;
  @BeforeEach void setup()throws Exception {
    Path root=Files.createDirectory(temporary.resolve("project")),config=Files.createDirectory(temporary.resolve("config"));
    owner=new AgentRunContext("parent","session",root,"project");
    var source=new SQLiteDataSource();source.setUrl("jdbc:sqlite:"+temporary.resolve("children.db"));repository=new DurableSubAgentRepository(source,Clock.systemUTC());
    Files.writeString(config.resolve("reviewer.yaml"),SubAgentConfigurationTest.yaml("reviewer")+"evidenceTools: [readMultiFile]\n");
    Files.writeString(config.resolve("judge.yaml"),SubAgentConfigurationTest.yaml("judge").replace("tools: [readMultiFile]","tools: []"));
    registry=new SubAgentRegistry(config,new SubAgentDefinitionLoader(new SubAgentToolPolicy(Set.of("readMultiFile")),m->true));assertTrue(registry.reload().isEmpty());
    runner=mock(SubAgentRunner.class);when(runner.durableBaseline(any(),eq(owner))).thenReturn("baseline");
    properties=new SubAgentProperties();properties.setDurableEnabled(true);properties.setConsensusEnabled(true);
    service=new SubAgentConsensusService(properties,repository,runner,registry);
  }
  SubAgentConsensusService.Reference answer(String text,String hash,boolean evidence) {
    var child=repository.create(owner,"reviewer","same question",null,2,0,"baseline");String run=UUID.randomUUID().toString();repository.claim(owner,child.id(),0,run);
    String output="{\"status\":\"SUCCESS\",\"summary\":\"answer\",\"result\":{\"answer\":\""+text+"\",\"evidence\":"+(evidence?"[{\"evidenceId\":\"e-"+run+"\",\"tool\":\"readMultiFile\",\"outputSha256\":\""+hash+"\",\"quote\":\"observed\"}]":"[]")+"},\"warnings\":[]}";
    repository.complete(child.id(),run,"COMPLETED",output);return new SubAgentConsensusService.Reference(child.id(),repository.get(owner,child.id()).resultHash());
  }
  @Test void majorityDoesNotEraseDisagreementOrProvenance() {
    var refs=List.of(answer("yes","a".repeat(64),true),answer("yes","a".repeat(64),true),answer("no","b".repeat(64),true));
    var compared=service.compare(owner,refs);assertEquals("DISAGREEMENT",compared.status());assertTrue(compared.unresolved());assertFalse(compared.truthVerified());
    assertEquals(3,compared.sources().size());assertEquals(2,compared.answers().size());assertEquals(1,compared.sharedEvidenceHashes().size());
    assertEquals(refs.getFirst().resultHash(),compared.sources().getFirst().resultHash());verifyNoInteractionsExceptBaseline();
  }
  void verifyNoInteractionsExceptBaseline(){verify(runner,never()).run(anyString(),anyString(),any(),any());}
  @Test void agreementWithoutEvidenceRemainsUnresolved() {
    var compared=service.compare(owner,List.of(answer("same","a".repeat(64),false),answer("same","b".repeat(64),false)));
    assertEquals("UNRESOLVED",compared.status());assertTrue(compared.unresolved());assertFalse(compared.truthVerified());
  }
  @Test void supportedAgreementReportsOnlyAgreementAndKeepsIndependentRunIds() {
    var compared=service.compare(owner,List.of(answer("same","a".repeat(64),true),answer("same","b".repeat(64),true)));
    assertEquals("AGREEMENT_REPORTED",compared.status());assertFalse(compared.truthVerified());
    assertEquals(2,compared.sources().stream().map(SubAgentConsensusService.Source::runId).distinct().count());
  }
  @Test void ownerHashAndDuplicateGuardsFailBeforeAnyJudge() {
    var first=answer("yes","a".repeat(64),true);var second=answer("no","b".repeat(64),true);
    assertThrows(IllegalArgumentException.class,()->service.compare(owner,List.of(first,first)));
    assertThrows(IllegalArgumentException.class,()->service.compare(owner,List.of(first,new SubAgentConsensusService.Reference(second.childId(),"f".repeat(64)))));
    var other=new AgentRunContext("other","other-session",owner.projectRoot(),owner.projectId());
    assertThrows(IllegalArgumentException.class,()->service.compare(other,List.of(first,second)));verifyNoInteractionsExceptBaseline();
  }
  @Test void boundsDisabledSettingsAndDifferentTasksRejectBeforeJudging() {
    var first=answer("yes","a".repeat(64),true);var second=answer("yes","b".repeat(64),true);
    assertThrows(IllegalArgumentException.class,()->service.compare(owner,List.of(first)));
    assertThrows(IllegalArgumentException.class,()->service.compare(owner,java.util.Collections.nCopies(9,first)));
    properties.setConsensusEnabled(false);assertThrows(IllegalArgumentException.class,()->service.compare(owner,List.of(first,second)));properties.setConsensusEnabled(true);
    var different=repository.create(owner,"reviewer","a different task",null,2,0,"baseline");repository.claim(owner,different.id(),0,"other-run");
    repository.complete(different.id(),"other-run","COMPLETED",repository.get(owner,second.childId()).result());
    var ref=new SubAgentConsensusService.Reference(different.id(),repository.get(owner,different.id()).resultHash());
    assertThrows(IllegalArgumentException.class,()->service.compare(owner,List.of(first,ref)));
    verifyNoInteractionsExceptBaseline();
  }
  @Test void optionalJudgeUsesSharedBudgetAndCannotReplaceUnresolvedEvidence() {
    var references=List.of(answer("yes","a".repeat(64),true),answer("no","b".repeat(64),true));
    var events=new AgentEventFactory(Clock.systemUTC());var run=new RunExecutionContext("parent",new OutputLimitRunBudget(0,1),null,events,event->{});run.setRunContext(owner);
    properties.setConsensusJudgeEnabled(true);
    when(runner.run(eq("judge"),anyString(),anyString(),any())).thenAnswer(call->{
      assertTrue(call.getArgument(2,String.class).contains("untrusted"));
      var reservation=call.getArgument(3,OutputLimitRunBudget.LlmCallReservation.class);assertTrue(reservation.tryReserve());assertEquals(0,run.sharedLlmReservation().remaining());
      return new SubAgentResult("judge","judge-run",SubAgentResult.Status.COMPLETED,"judge prefers yes",Instant.now(),Instant.now());
    });
    var judged=service.judge(run,references,"judge");assertTrue(judged.comparison().unresolved());assertEquals("DISAGREEMENT",judged.comparison().status());
    assertEquals(0,run.sharedLlmReservation().remaining());assertEquals("judge-run",judged.judge().subAgentRunId());
    assertThrows(IllegalArgumentException.class,()->service.judge(run,references,"reviewer"));
  }
  @Test void definitionChangeAndMissingContractCannotUpgradeClaimedEvidence() {
    var refs=List.of(answer("same","a".repeat(64),true),answer("same","b".repeat(64),true));
    when(runner.durableBaseline(any(),eq(owner))).thenReturn("changed");
    var compared=service.compare(owner,refs);assertEquals("UNRESOLVED",compared.status());assertTrue(compared.unresolved());
    assertTrue(compared.sources().stream().noneMatch(SubAgentConsensusService.Source::evidenceValidated));
  }
}
