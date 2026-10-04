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

  public SubAgentResult delegateTask(String agent,String task,String context) {return runner.run(agent,task,context);}
  public ParallelSubAgentDelegator.Batch delegateTasks(java.util.List<ParallelSubAgentDelegator.Request> requests) {return delegateTasks(requests,null);}
  @Tool(name = "delegateTask", description = "Delegate a bounded task to an independent SubAgent and return its final result. Pass only necessary context; honor explicit requests to use an agent.")
  public SubAgentResult delegateTask(String agent, String task, @ToolParam(required = false) String context,ToolContext toolContext) {
    var reservation=reservation(toolContext);
    return reservation==null?runner.run(agent,task,context):runner.run(agent,task,context,reservation);
  }
  @Tool(name="delegateTasks", description="Delegate 1 to 8 independent tasks in parallel to SubAgents, at most 2 active globally, with a shared 120s batch deadline. Return individual results in request order. Honor explicit user requests to delegate; do not use for dependent tasks.")
  public ParallelSubAgentDelegator.Batch delegateTasks(java.util.List<ParallelSubAgentDelegator.Request> requests,ToolContext toolContext) {
    if(parallel==null)throw new IllegalStateException("Parallel delegation unavailable");
    var reservation=reservation(toolContext);
    return reservation==null?parallel.delegate(requests):parallel.delegate(requests,reservation);
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
      public String call(String input) { return call(input,null); }
      public String call(String input, ToolContext context) {
        // Spring requires a nonempty context for methods declaring ToolContext, even without a shared budget.
        var supplied=context==null||context.getContext().isEmpty()?new ToolContext(java.util.Map.of("toolCallId",java.util.UUID.randomUUID().toString())):context;
        return delegate.call(input,supplied);
      }
    };
  }
  private dev.mikoto2000.rei.llm.OutputLimitRunBudget.LlmCallReservation reservation(ToolContext context) {
    if(context!=null&&context.getContext().get(dev.mikoto2000.rei.core.stagnation.RunExecutionContext.KEY) instanceof dev.mikoto2000.rei.core.stagnation.RunExecutionContext execution)return execution.sharedLlmReservation();
    return null;
  }
}
