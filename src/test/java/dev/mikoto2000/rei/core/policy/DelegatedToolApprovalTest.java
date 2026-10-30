package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.event.AgentEventFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 委譲済みツール承認の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class DelegatedToolApprovalTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** 検証に使う保存と検索の窓口。 */
  ToolApprovalRepository repository;

  /** 検証に使う利用条件の確認。 */
  ToolPermissionGuard guard;

  /** 検証に使う親、子の実行。 */
  AgentRunContext parent, child;

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    var clock = Clock.fixed(Instant.parse("2026-10-06T00:00:00Z"), ZoneOffset.UTC);
    repository =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + directory.resolve("approval.db")), clock);
    guard =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(true, Set.of(), Set.of(), Map.of())),
            new AgentEventFactory(clock),
            event -> {});
    guard.setApprovals(repository);
    parent = new AgentRunContext("parent", "session", directory, "project");
    child = new AgentRunContext("child", "subagent:child", directory, "project");
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void parentGrantIsExactAndConsumedOnceByChild() {
    var request = repository.request("readMultiFile", "{}", parent);
    repository.decide("project", request.id(), true);
    assertThrows(ToolPermissionException.class, () -> guard.check("readMultiFile", "{}", child));
    assertThrows(
        ToolPermissionException.class,
        () -> guard.checkDelegated("readMultiFile", "{ }", child, parent));
    assertEquals("APPROVED", repository.get("project", request.id()).status());
    assertDoesNotThrow(() -> guard.checkDelegated("readMultiFile", "{}", child, parent));
    assertEquals("CONSUMED", repository.get("project", request.id()).status());
    assertThrows(
        ToolPermissionException.class,
        () -> guard.checkDelegated("readMultiFile", "{}", child, parent));
  }

  /** ツール利用の権限制御で、実施してはいけない操作や影響が、指定された条件で発生しないことを確認する。 */
  @Test
  void mismatchAndNonReadActionsCannotBorrowParentGrant() {
    var request = repository.request("readMultiFile", "{}", parent);
    repository.decide("project", request.id(), true);
    for (var wrong :
        List.of(
            new AgentRunContext("child", "subagent:child", directory, "other"),
            new AgentRunContext("child", "subagent:child", directory.resolve("other"), "project"),
            new AgentRunContext("child", "ordinary", directory, "project"))) {
      assertThrows(
          ToolPermissionException.class,
          () -> guard.checkDelegated("readMultiFile", "{}", wrong, parent));
    }
    var write =
        repository.request(
            "writeMultiFile", "{\"files\":[{\"path\":\"a.txt\",\"content\":\"fixture\"}]}", parent);
    repository.decide("project", write.id(), true);
    assertThrows(
        ToolPermissionException.class,
        () ->
            guard.checkDelegated(
                "writeMultiFile",
                "{\"files\":[{\"path\":\"a.txt\",\"content\":\"fixture\"}]}",
                child,
                parent));
    assertEquals("APPROVED", repository.get("project", request.id()).status());
    assertEquals("APPROVED", repository.get("project", write.id()).status());
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void missingGrantCreatesReviewableRequestOwnedByParentSession() {
    assertThrows(
        ToolPermissionException.class,
        () -> guard.checkDelegated("readMultiFile", "{}", child, parent));
    var request = repository.list("project").getFirst();
    assertEquals(parent.conversationId(), request.sessionId());
    assertEquals(parent.runId(), request.runId());
    repository.decide("project", request.id(), true);
    assertDoesNotThrow(() -> guard.checkDelegated("readMultiFile", "{}", child, parent));
  }

  /** ツール利用の権限制御で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void explicitDenialStillWinsAndConcurrentChildrenShareOneGrant() throws Exception {
    var request = repository.request("readMultiFile", "{}", parent);
    repository.decide("project", request.id(), true);
    var denying =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true, Set.of(), Set.of(ActionCapability.READ), Map.of())),
            new AgentEventFactory(Clock.systemUTC()),
            event -> {});
    denying.setApprovals(repository);
    assertThrows(
        ToolPermissionException.class,
        () -> denying.checkDelegated("readMultiFile", "{}", child, parent));
    assertEquals("APPROVED", repository.get("project", request.id()).status());
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                try {
                  guard.checkDelegated("readMultiFile", "{}", child, parent);
                  return true;
                } catch (ToolPermissionException refused) {
                  return false;
                }
              });
      var second =
          executor.submit(
              () -> {
                try {
                  guard.checkDelegated(
                      "readMultiFile",
                      "{}",
                      new AgentRunContext("other", "subagent:other", directory, "project"),
                      parent);
                  return true;
                } catch (ToolPermissionException refused) {
                  return false;
                }
              });
      assertNotEquals(first.get(), second.get());
    }
    assertEquals("CONSUMED", repository.get("project", request.id()).status());
  }
}
