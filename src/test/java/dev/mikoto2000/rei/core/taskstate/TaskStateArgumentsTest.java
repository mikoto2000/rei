package dev.mikoto2000.rei.core.taskstate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** タスク状態引数の結果と状態の契約を検証するテスト。 */
class TaskStateArgumentsTest {
  /** 作業状態の記録の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void stringListsAreRejectedBeforeApprovalRequestOrConsumption() {
    var approvals = mock(ToolApprovalRepository.class);
    var guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    guard.setApprovals(approvals);
    String input =
        "{\"goal\":\"review\",\"status\":\"IN_PROGRESS\",\"completed\":[\"read\"],\"pending\":\"delegate\\\", \\\"verify\"}";
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                guard.check(
                    "updateTaskState",
                    input,
                    new AgentRunContext("run", "session", Path.of("."), "project")));
    assertTrue(error.getMessage().contains("pending: must be a JSON array of strings"));
    assertTrue(error.getMessage().contains("[\"review\",\"verify\"]"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(approvals);
  }

  /** 作業状態の記録で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void allTypeProblemsAreReportedTogetherWithoutInputValues() {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                TaskStateArguments.validate(
                    "updateTaskState",
                    "{\"goal\":{},\"status\":\"private-secret\",\"completed\":\"private-completed\",\"pending\":[17,false,{}]}"));
    for (String path :
        List.of("goal:", "status:", "completed:", "pending[0]:", "pending[1]:", "pending[2]:"))
      assertTrue(error.getMessage().contains(path));
    assertFalse(error.getMessage().contains("private-secret"));
    assertFalse(error.getMessage().contains("private-completed"));
  }

  /** 作業状態の記録で、条件が変わっても、引き継ぐべき情報や状態を保持することを確認する。 */
  @Test
  void optionalAndIgnoredValuesKeepExistingTaskStateCompatibility() {
    for (String status : List.of("IDLE", "IN_PROGRESS", "COMPLETED", "BLOCKED")) {
      assertDoesNotThrow(
          () -> TaskStateArguments.validate("updateTaskState", "{\"status\":\"" + status + "\"}"));
      assertDoesNotThrow(
          () ->
              TaskStateArguments.validate(
                  "updateTaskState",
                  "{\"goal\":null,\"status\":\""
                      + status
                      + "\",\"completed\":null,\"pending\":[null,\"\",\"review\"]}"));
    }
    assertDoesNotThrow(
        () ->
            TaskStateArguments.validate(
                "updateTaskState",
                "{\"goal\":\"\",\"status\":\"IN_PROGRESS\",\"completed\":[],\"pending\":[]}"));
    assertDoesNotThrow(
        () -> TaskStateArguments.validate("readMultiFile", "any unsupported tool input"));
  }

  /** 作業状態の記録で、再送や重複を、受付と実行の規則に従って扱うことを確認する。 */
  @Test
  void malformedDuplicateTrailingAndNonObjectJsonAreRejected() {
    for (String input :
        List.of(
            "not json",
            "[]",
            "null",
            "{\"status\":\"IDLE\",\"status\":\"IN_PROGRESS\"}",
            "{\"status\":\"IDLE\"} {}"))
      assertThrows(
          InvalidToolArgumentsException.class,
          () -> TaskStateArguments.validate("updateTaskState", input));
  }

  /** 作業状態の記録で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void diagnosticsRemainBoundedForLargeInvalidArrays() {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                TaskStateArguments.validate(
                    "updateTaskState",
                    "{\"status\":\"IN_PROGRESS\",\"pending\":["
                        + String.join(",", Collections.nCopies(1000, "false"))
                        + "]}"));
    assertTrue(error.getMessage().contains("992 additional items"));
    assertTrue(error.getMessage().length() < 1200);
  }
}
