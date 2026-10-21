package dev.mikoto2000.rei.doctor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.PermissionDecision;
import dev.mikoto2000.rei.core.policy.ToolPermissionException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.AgentEventPublisher;
import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 環境診断権限利用条件の確認の結果と状態の契約を検証するテスト。 */
class DoctorPermissionGuardTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path root;

  /** 実行環境の診断で、音声の取得・認識・入力の受け渡しが、検証条件に従うことを確認する。 */
  @Test
  void actualGuardMaintainsConversationReadonlyAndVoiceBoundaries() {
    var policy =
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true, EnumSet.allOf(ActionCapability.class), Set.of(), Map.of()));
    var guard =
        new ToolPermissionGuard(
            policy, new AgentEventFactory(Clock.systemUTC()), mock(AgentEventPublisher.class));
    var conversation =
        new AgentRunContext(
            "run",
            "session",
            root,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.CONVERSATION);
    assertThrows(
        ToolPermissionException.class,
        () -> guard.check("doctorInference", "fingerprint", conversation));
    var readonly =
        new AgentRunContext(
            "run",
            "session",
            root,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.READ_ONLY);
    assertDoesNotThrow(() -> guard.check("doctorConnectivity", "fingerprint", readonly));
    assertThrows(
        ToolPermissionException.class,
        () -> guard.check("doctorCodexVersion", "fingerprint", readonly));
    assertThrows(
        ToolPermissionException.class,
        () -> guard.check("doctorMicrophone", "fingerprint", readonly));
    var voice =
        new AgentRunContext(
            "run",
            "session",
            root,
            "project",
            AgentRunContext.RequestSource.SHELL,
            AgentRunContext.Mode.EXCLUSIVE,
            true);
    var failure =
        assertThrows(
            ToolPermissionException.class,
            () -> guard.check("doctorMicrophone", "fingerprint", voice));
    assertEquals(PermissionDecision.REQUIRE_APPROVAL, failure.decision());
  }
}
