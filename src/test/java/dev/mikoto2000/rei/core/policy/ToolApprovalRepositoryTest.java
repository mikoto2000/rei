package dev.mikoto2000.rei.core.policy;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** ツール承認保存と検索の窓口の結果と状態の契約を検証するテスト。 */
@Tag("integration")
class ToolApprovalRepositoryTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path dir;

  /** 日時や期限の条件を再現するために使う検証時刻。 */
  final Instant now = Instant.parse("2026-10-04T00:00:00Z");

  /** 検証に使う保存と検索の窓口。 */
  ToolApprovalRepository repository;

  /** {@code owner} の条件を再現するための補助情報を返す。 */
  AgentRunContext owner(String project, String session, String run) {
    return new AgentRunContext(run, session, dir, project);
  }

  /** 各検証を独立した状態で始められるよう、依存先と検証データを準備する。 */
  @BeforeEach
  void setup() {
    repository =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("approval.db")),
            Clock.fixed(now, ZoneOffset.UTC));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void exactApprovalSurvivesRestartAndIsConsumedOnce() {
    var request = repository.request("writeMultiFile", "{\"path\":\"a\"}", owner("p", "s", "r"));
    assertEquals(
        request.id(),
        repository.request("writeMultiFile", "{\"path\":\"a\"}", owner("p", "s", "r")).id());
    repository.decide("p", request.id(), true);
    var reopened =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("approval.db")),
            Clock.fixed(now, ZoneOffset.UTC));
    assertTrue(reopened.consume("writeMultiFile", "{\"path\":\"a\"}", owner("p", "s", "resume")));
    assertFalse(
        repository.consume("writeMultiFile", "{\"path\":\"a\"}", owner("p", "s", "resume")));
  }

  /** ツール利用の権限制御の操作が、指定された権限・承認条件に従うことを確認する。 */
  @Test
  void approvalCannotCrossArgumentsToolProjectOrSession() {
    var request = repository.request("writeMultiFile", "{}", owner("p", "s", "r"));
    repository.decide("p", request.id(), true);
    assertFalse(repository.consume("writeMultiFile", "{ }", owner("p", "s", "r")));
    assertFalse(repository.consume("other", "{}", owner("p", "s", "r")));
    assertFalse(repository.consume("writeMultiFile", "{}", owner("q", "s", "r")));
    assertFalse(repository.consume("writeMultiFile", "{}", owner("p", "t", "r")));
    assertFalse(
        repository.consume(
            "writeMultiFile", "{}", new AgentRunContext("r", "s", dir.resolve("moved"), "p")));
    assertThrows(IllegalArgumentException.class, () -> repository.decide("q", request.id(), true));
    assertTrue(repository.consume("writeMultiFile", "{}", owner("p", "s", "r")));
  }

  /** ツール利用の権限制御で、時刻や期限に応じて、待機・実行・保存の扱いを切り替えることを確認する。 */
  @Test
  void denialAndExpiryCannotBeApprovedOrConsumed() {
    var request = repository.request("writeMultiFile", "{}", owner("p", "s", "r"));
    repository.decide("p", request.id(), false);
    assertThrows(IllegalStateException.class, () -> repository.decide("p", request.id(), true));
    assertFalse(repository.consume("writeMultiFile", "{}", owner("p", "s", "r")));
    var expiring = repository.request("writeMultiFile", "{}", owner("p", "s", "r2"));
    var approved =
        repository.request("writeMultiFile", "{\"path\":\"expired\"}", owner("p", "s", "r3"));
    repository.decide("p", approved.id(), true);
    var later =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("approval.db")),
            Clock.fixed(now.plusSeconds(900), ZoneOffset.UTC));
    assertThrows(IllegalStateException.class, () -> later.decide("p", expiring.id(), true));
    assertFalse(later.consume("writeMultiFile", "{}", owner("p", "s", "r2")));
    assertFalse(later.consume("writeMultiFile", "{\"path\":\"expired\"}", owner("p", "s", "r3")));
  }

  /** ツール利用の権限制御で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void requestsAreReviewableButCredentialsAreRedactedAndInputsAreBounded() {
    var request =
        repository.request(
            "custom", "{\"api_key\":\"secret-123\",\"path\":\"a\"}", owner("p", "s", "r"));
    assertFalse(request.argumentsPreview().contains("secret-123"));
    assertTrue(request.argumentsPreview().contains("path"));
    assertThrows(
        IllegalArgumentException.class,
        () -> repository.request("custom", "a".repeat(16385), owner("p", "s", "r")));
  }

  /** ツール利用の権限制御で、競合する要求でも、実行順序と作業の分離を守ることを確認する。 */
  @Test
  void concurrentClientsCannotConsumeTheSameGrantTwice() throws Exception {
    var request = repository.request("writeMultiFile", "{}", owner("p", "s", "r"));
    repository.decide("p", request.id(), true);
    var other =
        new ToolApprovalRepository(
            new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("approval.db")),
            Clock.fixed(now, ZoneOffset.UTC));
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(() -> repository.consume("writeMultiFile", "{}", owner("p", "s", "r2")));
      var second =
          executor.submit(() -> other.consume("writeMultiFile", "{}", owner("p", "s", "r3")));
      assertNotEquals(first.get(), second.get());
    }
  }

  /** ツール利用の権限制御で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void guardedActionCanResumeOnceButDenialAlwaysWins() {
    var automatic =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true, java.util.Set.of(), java.util.Set.of(), java.util.Map.of()));
    var events = new java.util.ArrayList<dev.mikoto2000.rei.event.AgentEvent>();
    var guard =
        new ToolPermissionGuard(
            automatic,
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.fixed(now, ZoneOffset.UTC)),
            events::add);
    guard.setApprovals(repository);
    assertThrows(
        ToolPermissionException.class,
        () -> guard.check("writeMultiFile", "{}", owner("p", "s", "r")));
    var request = repository.list("p").getFirst();
    repository.decide("p", request.id(), true);
    var denying =
        new ToolPermissionGuard(
            new ToolPermissionPolicy(
                new ToolPermissionProperties(
                    true,
                    java.util.Set.of(),
                    java.util.Set.of(ActionCapability.LOCAL_WRITE),
                    java.util.Map.of())),
            new dev.mikoto2000.rei.event.AgentEventFactory(Clock.fixed(now, ZoneOffset.UTC)),
            events::add);
    denying.setApprovals(repository);
    assertThrows(
        ToolPermissionException.class,
        () -> denying.check("writeMultiFile", "{}", owner("p", "s", "r2")));
    assertEquals("APPROVED", repository.get("p", request.id()).status());
    assertDoesNotThrow(() -> guard.check("writeMultiFile", "{}", owner("p", "s", "r2")));
    assertThrows(
        ToolPermissionException.class,
        () -> guard.check("writeMultiFile", "{}", owner("p", "s", "r2")));
  }
}
