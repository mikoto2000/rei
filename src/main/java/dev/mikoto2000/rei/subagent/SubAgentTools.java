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
  private ParallelSubAgentDelegator parallel;
  @org.springframework.beans.factory.annotation.Autowired
  void parallelDelegator(ParallelSubAgentDelegator parallel) { this.parallel=parallel; }
  public SubAgentTools(SubAgentRunner runner, SubAgentRegistry registry) { this.runner = runner; this.registry = registry; }

  @Tool(name = "delegateTask", description = "Delegate a bounded task to an independent SubAgent and return its final result. Pass only necessary context; honor explicit requests to use an agent.")
  public SubAgentResult delegateTask(String agent, String task, @ToolParam(required = false) String context) {
    return runner.run(agent, task, context);
  }
  @Tool(name="delegateTasks", description="Delegate 1 to 8 independent tasks in parallel to SubAgents, at most 2 active globally, with a shared 120s batch deadline. Return individual results in request order. Honor explicit user requests to delegate; do not use for dependent tasks.")
  public ParallelSubAgentDelegator.Batch delegateTasks(java.util.List<ParallelSubAgentDelegator.Request> requests) {
    if(parallel==null)throw new IllegalStateException("Parallel delegation unavailable");
    return parallel.delegate(requests);
  }
  /** The catalog is read when tool definitions are requested, so cached parent clients see reloads. */
  public ToolCallback callback() {
    return callback("delegateTask");
  }
  public ToolCallback callback(String name) {
    ToolCallback delegate = java.util.Arrays.stream(MethodToolCallbackProvider.builder().toolObjects(this).build().getToolCallbacks())
        .filter(c->c.getToolDefinition().name().equals(name)).findFirst().orElseThrow();
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
