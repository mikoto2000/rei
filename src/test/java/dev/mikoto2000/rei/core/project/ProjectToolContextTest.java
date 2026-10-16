package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.AgentRunScope;
import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventFactory;
import dev.mikoto2000.rei.event.ToolEventCallbackDecorator;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class ProjectToolContextTest {
  @Test
  void toolContextCarriesOwnershipAcrossThreadBoundary() {
    var run =
        new AgentRunContext(
            "run",
            new ProjectContext(UUID.randomUUID().toString(), "a", Path.of("a")),
            "chat:main");
    var delegate =
        new ToolCallback() {
          public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                .name("test")
                .description("test")
                .inputSchema("{}")
                .build();
          }

          public String call(String input) {
            assertThat(AgentRunScope.current()).isEqualTo(run);
            return "ok";
          }

          public String call(String input, ToolContext context) {
            return call(input);
          }
        };
    List<AgentEvent> events = new ArrayList<>();
    new ToolEventCallbackDecorator(delegate, new AgentEventFactory(Clock.systemUTC()), events::add)
        .call("{}", new ToolContext(Map.of(AgentRunContext.class.getName(), run)));
    assertThat(events).allMatch(event -> run.projectId().equals(event.projectId()));
    assertThat(AgentRunScope.current()).isNull();
  }
}
