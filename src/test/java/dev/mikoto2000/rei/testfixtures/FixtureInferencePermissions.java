package dev.mikoto2000.rei.testfixtures;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunContinuation;
import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.InferenceExportGuard;
import dev.mikoto2000.rei.core.policy.ToolApprovalRepository;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

/** Explicit host policy for owned protocol fixtures; the real inference guard remains in use. */
public final class FixtureInferencePermissions {
  private FixtureInferencePermissions() {}

  /** Tests of SDK/storage mechanics explicitly permit synthetic sends, independently of tools. */
  public static RunContinuation automatic(DataSource source) {
    var clock = Clock.systemUTC();
    var guard = new InferenceExportGuard(new ToolApprovalRepository(source, clock), clock);
    guard.configurePolicy(
        new ToolPermissionPolicy(
            new ToolPermissionProperties(
                true,
                Set.of(ActionCapability.NETWORK_WRITE, ActionCapability.EXTERNAL_SIDE_EFFECT),
                Set.of(),
                Map.of())));
    return new RunContinuation() {
      public Saved restore(AgentRunContext owner) {
        return null;
      }

      public boolean suspend(RunExecutionContext execution, List<Message> history) {
        return false;
      }

      public Optional<Prompt> prepareInference(RunExecutionContext execution, Prompt prompt) {
        var pin = execution.progressSnapshot().connection();
        if (pin == null || !pin.named())
          throw new AssertionError("Explicit named fixture pin required");
        var decision = guard.authorize(execution.runContext(), pin, prompt);
        if (!decision.permitted())
          throw new AssertionError("Fixture send policy did not permit inference");
        return Optional.of(decision.prompt());
      }
    };
  }
}
