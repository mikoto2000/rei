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
  MESSAGE_STARTED("message.started"),
  MESSAGE_DELTA("message.delta"),
  MESSAGE_COMPLETED("message.completed"),
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
