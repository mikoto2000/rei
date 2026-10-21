package dev.mikoto2000.rei.subagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** 委譲引数集計の結果と状態の契約を検証するテスト。 */
class DelegationArgumentsAggregationTest {
  /** 全対象未指定外部選択可能な設定を伝えることを確認する。 */
  @Test
  void reportsAllMissingExternalOptionsAndContextConflictTogether() {
    String input =
        "{\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\",\"context\":\"PRIVATE CONTEXT\"}";
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () -> DelegationArguments.validate("delegateTask", input));
    for (String field :
        new String[] {"context", "accountRef", "modelId", "sourceRefs", "expectedOutput"})
      assertTrue(error.getMessage().contains("request." + field), field);
    assertFalse(error.getMessage().contains("PRIVATE CONTEXT"));
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void batchErrorsIdentifyEveryRequestRatherThanStoppingAtTheFirst() {
    String input =
        "{\"requests\":[{\"id\":\"same\",\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\"},{\"id\":\"same\",\"agent\":\"reviewer\",\"task\":\"review\",\"backendId\":\"chatgpt-plan\"}]}";
    var issues = DelegationArguments.problems("delegateTasks", input);
    assertTrue(issues.stream().anyMatch(s -> s.contains("requests[0].accountRef")));
    assertTrue(issues.stream().anyMatch(s -> s.contains("requests[1].modelId")));
    assertTrue(issues.stream().anyMatch(s -> s.contains("requests[1].id")));
  }

  /** 補助エージェントへの委譲で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void malformedFieldTypesDoNotHideOtherProblems() {
    var issues =
        DelegationArguments.problems(
            "delegateTask",
            "{\"agent\":false,\"task\":12,\"backendId\":\"chatgpt-plan\",\"sourceRefs\":true,\"expectedOutput\":\"not-json\"}");
    for (String field :
        new String[] {"agent", "task", "accountRef", "modelId", "sourceRefs", "expectedOutput"})
      assertTrue(issues.stream().anyMatch(s -> s.contains("request." + field)));
  }
}
