package dev.mikoto2000.rei.subagent;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class SubAgentConsensusRunnerIntegrationTest {
  @TempDir Path temporary;
  @Test void realRunnerJudgeSharesParentBudgetAndPersistsItsOwnProvenance()throws Exception {
    var fixture=new SubAgentDagServiceTest();fixture.temporary=temporary;
    try {
      fixture.setup(false);fixture.properties.setConsensusEnabled(true);fixture.properties.setConsensusJudgeEnabled(true);
      var graph=fixture.service.submit(fixture.run,new SubAgentDagSpec(List.of(
        new SubAgentDagSpec.Node("a","reviewer","same question",null,List.of()),
        new SubAgentDagSpec.Node("b","reviewer","same question",null,List.of())),SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT,2));
      var service=new SubAgentConsensusService(fixture.properties,fixture.repository,fixture.runner,fixture.registry);
      var references=graph.nodes().stream().map(node->new SubAgentConsensusService.Reference(node.childId(),node.resultHash())).toList();
      // The fixture model claims an answer but its agent has no evidence contract.
      var judged=service.judge(fixture.run,references,"reviewer");assertEquals("UNRESOLVED",judged.comparison().status());
      assertEquals(SubAgentResult.Status.COMPLETED,judged.judge().status());assertNotNull(judged.judge().durableTaskId());
      var receipt=fixture.repository.get(fixture.parent,judged.judge().durableTaskId());
      assertEquals(judged.judge().subAgentRunId(),receipt.run());assertEquals(1,receipt.consumedCalls());
      assertEquals(5,fixture.run.sharedLlmReservation().remaining());assertEquals(3,fixture.prompts.size());
      assertEquals(2,fixture.repository.get(fixture.parent,graph.graphId()).consumedCalls());
    }finally{fixture.close();}
  }
}
