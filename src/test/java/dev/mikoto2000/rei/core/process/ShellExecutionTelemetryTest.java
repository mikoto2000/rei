package dev.mikoto2000.rei.core.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mikoto2000.rei.core.policy.ActionCapability;
import dev.mikoto2000.rei.core.policy.InvalidToolArgumentsException;
import dev.mikoto2000.rei.core.policy.ToolPermissionGuard;
import dev.mikoto2000.rei.core.policy.ToolPermissionPolicy;
import dev.mikoto2000.rei.core.policy.ToolPermissionProperties;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.InMemoryAgentEventBus;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ShellExecutionTelemetryTest {
  @Test
  void rejectsBeforeExecutionAreMeasuredSeparatelyWithoutCommandLabels() {
    var registry = new SimpleMeterRegistry();
    Metrics.addRegistry(registry);
    try {
      registry.counter(
          "rei.shell.executions",
          "mode",
          "foreground",
          "shell",
          "powershell",
          "outcome",
          "SUCCEEDED",
          "category",
          "NONE",
          "certainty",
          "unknown");
      var policy =
          new ToolPermissionPolicy(
              new ToolPermissionProperties(
                  true, Set.of(ActionCapability.READ), Set.of(ActionCapability.EXECUTE), Map.of()));
      var guard =
          new ToolPermissionGuard(
              policy, new AgentEventFactory(Clock.systemUTC()), new InMemoryAgentEventBus());
      assertThrows(
          InvalidToolArgumentsException.class,
          () ->
              guard.validateArguments(
                  "runCommand", "{\"request\":{\"command\":\"Get-Content private.txt\"}}"));
      assertThrows(
          RuntimeException.class,
          () -> guard.check("runCommand", "{\"request\":{\"command\":\"git status\"}}", null));
      assertEquals(
          1.0,
          registry
              .get("rei.shell.rejections")
              .tag("reason", "invalid_arguments")
              .counter()
              .count());
      assertEquals(
          1.0, registry.get("rei.shell.rejections").tag("reason", "denied").counter().count());
      assertEquals(
          0.0,
          registry.find("rei.shell.executions").counters().stream()
              .mapToDouble(io.micrometer.core.instrument.Counter::count)
              .sum());
      assertTrue(ToolPermissionPolicy.intrinsicallyReadOnly("getShellEnvironment"));
    } finally {
      Metrics.removeRegistry(registry);
      registry.close();
    }
  }
}
