package dev.mikoto2000.rei.subagent;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class SubAgentTools {
  private final SubAgentRunner runner;
  private final SubAgentRegistry registry;
  public SubAgentTools(SubAgentRunner runner, SubAgentRegistry registry) { this.runner = runner; this.registry = registry; }

  @Tool(name = "delegateTask", description = "Delegate a bounded task to an independent SubAgent and return its final result. Pass only necessary context; honor explicit requests to use an agent.")
  public SubAgentResult delegateTask(String agent, String task, @ToolParam(required = false) String context) {
    return runner.run(agent, task, context);
  }
  /** The catalog is read when tool definitions are requested, so cached parent clients see reloads. */
  public ToolCallback callback() {
    ToolCallback delegate = MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks()[0];
    return new ToolCallback() {
      public ToolDefinition getToolDefinition() {
        var original = delegate.getToolDefinition();
        String catalog = registry.list().stream().map(d -> d.id() + " | " + d.name() + " | " + d.description())
            .collect(java.util.stream.Collectors.joining("\n"));
        return ToolDefinition.builder().name(original.name()).inputSchema(original.inputSchema())
            .description(original.description() + "\nAvailable SubAgents (id | name | description):\n" + catalog).build();
      }
      public String call(String input) { return delegate.call(input); }
      public String call(String input, ToolContext context) { return delegate.call(input, context); }
    };
  }
}
