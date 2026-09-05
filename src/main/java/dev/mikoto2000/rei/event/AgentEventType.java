package dev.mikoto2000.rei.event;

/**
 * Agent Event の種別。
 *
 * <p>文字列の直書きを各所に散らさず、型安全に扱えるようにするための enum。</p>
 */
public enum AgentEventType {
  AGENT_RUN_STARTED("agent.run.started"),
  AGENT_RUN_COMPLETED("agent.run.completed"),
  AGENT_RUN_FAILED("agent.run.failed"),
  LLM_REQUEST_STARTED("llm.request.started"),
  LLM_REQUEST_FAILED("llm.request.failed"),
  LLM_RESPONSE_FIRST_TOKEN("llm.response.first_token"),
  LLM_RESPONSE_COMPLETED("llm.response.completed"),
  MESSAGE_STARTED("message.started"),
  MESSAGE_DELTA("message.delta"),
  MESSAGE_COMPLETED("message.completed"),
  MEMORY_CONSOLIDATION_SUGGESTED("memory.consolidation.suggested"),
  THINKING_STARTED("thinking.started"),
  THINKING_DELTA("thinking.delta"),
  THINKING_COMPLETED("thinking.completed"),
  TOOL_STARTED("tool.started"),
  TOOL_COMPLETED("tool.completed"),
  TOOL_FAILED("tool.failed"),
  SKILL_SELECTION_STARTED("skill.selection.started"),
  SKILL_SELECTION_COMPLETED("skill.selection.completed"),
  SKILL_SELECTION_FAILED("skill.selection.failed"),
  SKILL_ROUTING_STARTED("skill.routing.started"),
  SKILL_ROUTING_COMPLETED("skill.routing.completed"),
  SKILL_ROUTING_FAILED("skill.routing.failed"),
  SKILL_CANDIDATES_EVALUATED("skill.candidates.evaluated"),
  TASK_CREATED("task.created"),
  TASK_STARTED("task.started"),
  TASK_COMPLETED("task.completed"),
  TASK_FAILED("task.failed"),
  WORKING_SET_ITEM_ADDED("working_set.item.added"),
  WORKING_SET_ITEM_REMOVED("working_set.item.removed"),
  WORKING_SET_SEARCH_STARTED("working_set.search.started"),
  WORKING_SET_SEARCH_COMPLETED("working_set.search.completed"),
  WORKING_SET_CONTEXT_INJECTED("working_set.context.injected"),
  CONTEXT_INJECTED("context.injected"),
  CONTEXT_BUDGET_EVALUATED("context.budget.evaluated"),
  CONTEXT_BUDGET_TRIMMED("context.budget.trimmed"),
  FILE_SUMMARY_SAVED("file_summary.saved"),
  FILE_SUMMARY_INVALIDATED("file_summary.invalidated"),
  FILE_SUMMARY_STALE_SKIPPED("file_summary.stale_skipped"),
  CHECKPOINT_SAVED("checkpoint.saved"),
  BACKGROUND_PROCESS_STARTED("background_process.started"),
  BACKGROUND_PROCESS_COMPLETED("background_process.completed"),
  BACKGROUND_PROCESS_FAILED("background_process.failed"),
  BACKGROUND_PROCESS_KILLED("background_process.killed"),
  TOPIC_GENERATION_STARTED("topic.generation.started"),
  TOPIC_IDLE_TRIGGER_EVALUATED("topic.idle_trigger.evaluated"),
  TOPIC_CANDIDATES_REFRESHED("topic.candidates.refreshed"),
  TOPIC_CANDIDATE_GENERATED("topic.candidate.generated"),
  TOPIC_CANDIDATE_SCORED("topic.candidate.scored"),
  TOPIC_CANDIDATE_REJECTED("topic.candidate.rejected"),
  TOPIC_SELECTED("topic.selected"),
  TOPIC_SPOKEN("topic.spoken"),
  TOPIC_SPEAK_SKIPPED("topic.speak.skipped"),
  TOPIC_AUTO_SPEAK_SUPPRESSED("topic.auto_speak.suppressed"),
  TOPIC_GENERATION_COMPLETED("topic.generation.completed"),
  TOPIC_GENERATION_FAILED("topic.generation.failed"),
  CONTEXT_SNAPSHOT_UPDATED("context.snapshot.updated"),
  FILE_CREATED("file.created"),
  FILE_MODIFIED("file.modified"),
  FILE_DELETED("file.deleted");

  private final String value;

  AgentEventType(String value) {
    this.value = value;
  }

  /** ワイヤー上の文字列表現。 */
  public String value() {
    return value;
  }

  /** 文字列から種別を解決する。 */
  public static AgentEventType from(String value) {
    for (AgentEventType type : values()) {
      if (type.value.equals(value)) {
        return type;
      }
    }
    throw new IllegalArgumentException("Unknown event type: " + value);
  }
}
