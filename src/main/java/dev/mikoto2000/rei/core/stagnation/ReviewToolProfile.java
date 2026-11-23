package dev.mikoto2000.rei.core.stagnation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Run-local tool selection. Filtering never registers authority absent from the original prompt.
 */
public final class ReviewToolProfile {
  private static final String KEY = "rei.reviewToolProfile";
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();
  public static final String TOOL = "setReviewToolProfile";
  private static final Set<String> REVIEW =
      Set.of(
          "beginRepositoryReview",
          "scanReviewSnapshot",
          "reviewTaskStatus",
          "listReviewTargets",
          "listReviewTasks",
          "listReviewTaskResults",
          "resumeRepositoryReview",
          "claimReviewWorkItem",
          "readReviewWorkItem",
          "readNextReviewWorkItem",
          "readNextReviewWorkItems",
          "submitReviewWorkItem",
          "beginReview",
          "restartReview",
          "readReviewFile",
          "readReviewPages",
          "submitReviewResult",
          "submitReviewPage",
          "submitReviewPages",
          "dismissReviewFinding",
          "reviewStatus",
          "reviewFindings",
          "reviewPending",
          "listFile",
          "findFile",
          "countFileLines",
          "readFile",
          "readMultiFile",
          "readSkillResource",
          "readRawToolResult",
          "grepMultiQuery",
          "searchAndRead",
          "searchFilePage",
          "readPdfFile",
          "readPdfPage",
          "repositoryMap",
          "updateTaskState",
          "checkpointSave",
          "checkpointList",
          "checkpointShow",
          "checkpointInspect",
          "checkpointPlan",
          "checkpointAnnotate",
          "checkpointConfirm",
          "now",
          "today",
          TOOL);

  private volatile boolean enabled;

  private ReviewToolProfile() {}

  /** Install only for the repository-review protocol, once per dispatched model stream. */
  public static Prompt install(Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) return prompt;
    var callbacks = options.getToolCallbacks();
    if (callbacks == null
        || callbacks.stream().anyMatch(c -> c.getToolDefinition().name().equals(TOOL)))
      return prompt;
    var names = callbacks.stream().map(c -> c.getToolDefinition().name()).toList();
    if (!names.contains("beginReview") || !names.contains("readReviewFile")) return prompt;
    var state = new ReviewToolProfile();
    var context = new HashMap<String, Object>();
    if (options.getToolContext() != null) context.putAll(options.getToolContext());
    context.put(KEY, state);
    var registered = new ArrayList<>(callbacks);
    registered.add(state.callback());
    return new Prompt(
        prompt.getInstructions(),
        options.mutate().toolContext(context).toolCallbacks(registered).build());
  }

  /**
   * Select from existing callbacks on each iteration; keep full options for subsequent recovery.
   */
  public static Prompt select(Prompt prompt) {
    if (!(prompt.getOptions() instanceof ToolCallingChatOptions options)) return prompt;
    var context = options.getToolContext();
    var callbacks = options.getToolCallbacks();
    if (context == null
        || callbacks == null
        || !(context.get(KEY) instanceof ReviewToolProfile state)) return prompt;
    if (context.get(RunExecutionContext.KEY) instanceof RunExecutionContext run
        && run.toolDiscovery() != null)
      run.toolDiscovery().retainView(() -> state.enabled ? Set.of(TOOL) : Set.of());
    if (!state.enabled) return prompt;
    var selected = reviewCallbacks(callbacks);
    if (context.get(RunExecutionContext.KEY) instanceof RunExecutionContext run
        && run.toolDiscovery() != null) {
      var basic = run.toolDiscovery().basicNames();
      selected =
          callbacks.stream()
              .filter(
                  c ->
                      REVIEW.contains(c.getToolDefinition().name())
                          || basic.contains(c.getToolDefinition().name())
                          || c.getToolDefinition().name().equals("searchTools"))
              .toList();
    }
    return new Prompt(prompt.getInstructions(), options.mutate().toolCallbacks(selected).build());
  }

  /** Stable explicit allowlist, shared with the reproducible schema comparison. */
  public static List<ToolCallback> reviewCallbacks(List<ToolCallback> callbacks) {
    return callbacks.stream().filter(c -> REVIEW.contains(c.getToolDefinition().name())).toList();
  }

  private ToolCallback callback() {
    var definition =
        ToolDefinition.builder()
            .name(TOOL)
            .description(
                "Before repository review, call with enabled=true to show only review,"
                    + " source-reading and recovery tools on subsequent turns. Call with"
                    + " enabled=false before repairs, commands, web research or other work to"
                    + " restore the authorized tools already available to this run. Where tool"
                    + " discovery is enabled, unpublished specialist tools still require"
                    + " searchTools. This changes visibility only, not permissions or progress. The"
                    + " switch lasts only for this run.")
            .inputSchema(
                "{\"type\":\"object\",\"properties\":{\"enabled\":{\"type\":\"boolean\"}},\"required\":[\"enabled\"],\"additionalProperties\":false}")
            .build();
    return new ToolCallback() {
      @Override
      public ToolDefinition getToolDefinition() {
        return definition;
      }

      @Override
      public String call(String input) {
        try {
          var json = JSON.readTree(input);
          if (json == null
              || !json.isObject()
              || json.size() != 1
              || !json.path("enabled").isBoolean())
            return "{\"success\":false,\"error\":\"enabled must be a boolean\"}";
          enabled = json.path("enabled").asBoolean();
          return "{\"success\":true,\"reviewToolsOnly\":" + enabled + "}";
        } catch (tools.jackson.core.JacksonException invalid) {
          return "{\"success\":false,\"error\":\"enabled must be a boolean\"}";
        }
      }
    };
  }
}
