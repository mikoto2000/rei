package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 補助エージェント評価の合意実行の担当外部連携の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class SubAgentConsensusRunnerIntegrationTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path temporary;

  /** 補助エージェントへの委譲で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void realRunnerJudgeSharesParentBudgetAndPersistsItsOwnProvenance() throws Exception {
    var fixture = new SubAgentDagServiceTest();
    fixture.temporary = temporary;
    try {
      fixture.setup(false);
      fixture.properties.setConsensusEnabled(true);
      fixture.properties.setConsensusJudgeEnabled(true);
      var graph =
          fixture.service.submit(
              fixture.run,
              new SubAgentDagSpec(
                  List.of(
                      new SubAgentDagSpec.Node("a", "reviewer", "same question", null, List.of()),
                      new SubAgentDagSpec.Node("b", "reviewer", "same question", null, List.of())),
                  SubAgentDagSpec.FailurePolicy.CONTINUE_INDEPENDENT,
                  2));
      var service =
          new SubAgentConsensusService(
              fixture.properties, fixture.repository, fixture.runner, fixture.registry);
      var references =
          graph.nodes().stream()
              .map(
                  node -> new SubAgentConsensusService.Reference(node.childId(), node.resultHash()))
              .toList();
      // The fixture model claims an answer but its agent has no evidence contract.
      var judged = service.judge(fixture.run, references, "reviewer");
      assertEquals("UNRESOLVED", judged.comparison().status());
      assertEquals(SubAgentResult.Status.COMPLETED, judged.judge().status());
      assertNotNull(judged.judge().durableTaskId());
      var receipt = fixture.repository.get(fixture.parent, judged.judge().durableTaskId());
      assertEquals(judged.judge().subAgentRunId(), receipt.run());
      assertEquals(1, receipt.consumedCalls());
      assertEquals(5, fixture.run.sharedLlmReservation().remaining());
      assertEquals(3, fixture.prompts.size());
      assertEquals(2, fixture.repository.get(fixture.parent, graph.graphId()).consumedCalls());
    } finally {
      fixture.close();
    }
  }
}
