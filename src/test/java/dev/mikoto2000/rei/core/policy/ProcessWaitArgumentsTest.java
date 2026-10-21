package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import dev.mikoto2000.rei.core.ProcessDependencyTools;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.process.BackgroundProcessManager;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import tools.jackson.databind.json.JsonMapper;

/** プロセス待機引数の結果と状態の契約を検証するテスト。 */
class ProcessWaitArgumentsTest {
  /** 整数許容範囲を受け付けられることを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"processId\":\"p\"}",
        "{\"timeoutSeconds\":null}",
        "{\"timeoutSeconds\":0}",
        "{\"timeoutSeconds\":1}",
        "{\"timeoutSeconds\":10}",
        "{\"timeoutSeconds\":59}",
        "{\"timeoutSeconds\":60}"
      })
  void acceptsIntegerBoundsAndDefaultTimeout(String input) {
    assertDoesNotThrow(() -> ProcessWaitArguments.validate("waitForShellProcess", input));
  }

  /** 不正なタイムアウトを受け付けないことを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "-1",
        "61",
        "120",
        "2147483648",
        "4294967296",
        "999999999999999999999999999999",
        "-2147483649",
        "0.5",
        "60.0",
        "1e1",
        "true",
        "\"60\"",
        "\"private-value\"",
        "[]",
        "{}"
      })
  void rejectsInvalidTimeoutBeforeExecutionWithoutEchoingValues(String value) {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () ->
                ProcessWaitArguments.validate(
                    "waitForShellProcess",
                    "{\"processId\":\"private-id\",\"timeoutSeconds\":" + value + "}"));

    assertTrue(error.getMessage().contains("timeoutSeconds"));
    assertTrue(error.getMessage().contains("0〜60"));
    assertTrue(error.getMessage().contains("同じ processId"));
    assertTrue(error.getMessage().contains("実行していません"));
    assertFalse(error.getMessage().contains("private-id"));
    assertFalse(error.getMessage().contains("private-value"));
  }

  /** ツール利用の権限制御で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        " ",
        "not json",
        "null",
        "[]",
        "{",
        "{} {}",
        "{} true",
        "{\"timeoutSeconds\":0,\"timeoutSeconds\":120}"
      })
  void malformedArgumentsProduceRepairGuidance(String input) {
    var error =
        assertThrows(
            InvalidToolArgumentsException.class,
            () -> ProcessWaitArguments.validate("waitForShellProcess", input));

    assertTrue(error.getMessage().contains("単一JSONオブジェクト"));
    assertTrue(error.getMessage().contains("0〜60"));
  }

  /** ツール利用の権限制御で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void parsingIsBoundedAndUnsupportedToolsAreUnaffected() {
    for (String input :
        new String[] {
          null,
          " ".repeat(1_048_577),
          "[".repeat(40) + "0" + "]".repeat(40),
          "{\"timeoutSeconds\":" + "9".repeat(1001) + "}"
        }) {
      var error =
          assertThrows(
              InvalidToolArgumentsException.class,
              () -> ProcessWaitArguments.validate("waitForShellProcess", input));
      assertTrue(error.getMessage().length() < 1000);
    }
    assertDoesNotThrow(() -> ProcessWaitArguments.validate("runCommand", "not json"));
    assertDoesNotThrow(() -> ProcessWaitArguments.validate(null, null));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void invalidTimeoutIsRejectedBeforeApprovalIsRequestedOrConsumed() {
    var approvals = mock(ToolApprovalRepository.class);
    var policy =
        new ToolPermissionPolicy(new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of()));
    var guard =
        new ToolPermissionGuard(policy, new AgentEventFactory(Clock.systemUTC()), event -> {});
    guard.setApprovals(approvals);
    var owner = new AgentRunContext("run", "session", Path.of("."), "project");

    assertThrows(
        InvalidToolArgumentsException.class,
        () ->
            guard.check(
                "waitForShellProcess", "{\"processId\":\"p\",\"timeoutSeconds\":120}", owner));

    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(approvals);
  }

  /** ツール利用の権限制御で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void toolSchemaDescribesOptionalIntegerTimeoutAndItsBounds() {
    var processes = mock(BackgroundProcessManager.class);
    var callback =
        MethodToolCallbackProvider.builder()
            .toolObjects(new ProcessDependencyTools(processes))
            .build()
            .getToolCallbacks()[0];
    var schema = JsonMapper.builder().build().readTree(callback.getToolDefinition().inputSchema());
    var timeout = schema.path("properties").path("timeoutSeconds");

    assertTrue(timeout.path("description").asString().contains("0 to 60"));
    assertTrue(timeout.path("description").asString().contains("default 10 seconds"));
    assertFalse(schema.path("required").toString().contains("timeoutSeconds"));
    assertFalse(schema.path("properties").has("toolContext"));
    // 追加の処理を依頼していないことも確認し、想定外の副作用を防ぐ。
    verifyNoInteractions(processes);
  }
}
