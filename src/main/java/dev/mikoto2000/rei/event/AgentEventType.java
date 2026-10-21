package dev.mikoto2000.rei.event;

/**
 * Agent Event の種別。
 *
 * <p>文字列の直書きを各所に散らさず、型安全に扱えるようにするための enum。
 */
public enum AgentEventType {
  /** GITHUBPR更新。 */
  GITHUB_PR_UPDATED("github.pr.updated"),
  /** GITHUBPR統合済み。 */
  GITHUB_PR_MERGED("github.pr.merged"),
  /** GITHUBレビュー受付済み。 */
  GITHUB_REVIEW_SUBMITTED("github.review.submitted"),
  /** GITHUBCI失敗。 */
  GITHUB_CI_FAILED("github.ci.failed"),
  /** GITHUBWORKFLOW完了。 */
  GITHUB_WORKFLOW_COMPLETED("github.workflow.completed"),
  /** 依存条件更新。 */
  DEPENDENCY_UPDATED("dependency.updated"),
  /** 依存条件完了。 */
  DEPENDENCY_COMPLETED("dependency.completed"),
  /** 依存条件失敗。 */
  DEPENDENCY_FAILED("dependency.failed"),
  /** 依存条件キャンセル済み。 */
  DEPENDENCY_CANCELLED("dependency.cancelled"),
  /** 目標更新。 */
  GOAL_UPDATED("goal.updated"),
  /** 注意必須。 */
  ATTENTION_REQUIRED("attention.required"),
  /** 自動待機停止済み。 */
  AUTO_SLEEP_STOPPED("memory.auto-sleep.stopped"),
  /** 記憶待機開始。 */
  MEMORY_SLEEP_STARTED("memory.sleep.started"),
  /** 記憶待機完了。 */
  MEMORY_SLEEP_COMPLETED("memory.sleep.completed"),
  /** 記憶待機失敗。 */
  MEMORY_SLEEP_FAILED("memory.sleep.failed"),
  /** 記憶関連情報の取得完了。 */
  MEMORY_RETRIEVAL_COMPLETED("memory.retrieval.completed"),
  /** アプリケーション終了開始。 */
  APPLICATION_SHUTDOWN_STARTED("application.shutdown.started"),
  /** 文脈圧縮開始。 */
  CONTEXT_COMPRESSION_STARTED("context.compression.started"),
  /** 文脈圧縮完了。 */
  CONTEXT_COMPRESSION_COMPLETED("context.compression.completed"),
  /** 文脈圧縮失敗。 */
  CONTEXT_COMPRESSION_FAILED("context.compression.failed"),
  /** 文脈圧縮対象外。 */
  CONTEXT_COMPRESSION_SKIPPED("context.compression.skipped"),
  /** 委譲開始。 */
  DELEGATION_STARTED("delegation.started"),
  /** 委譲完了。 */
  DELEGATION_COMPLETED("delegation.completed"),
  /** 委譲失敗。 */
  DELEGATION_FAILED("delegation.failed"),
  /** 委譲キャンセル済み。 */
  DELEGATION_CANCELLED("delegation.cancelled"),
  /** 画面操作利用進捗。 */
  COMPUTER_USE_PROGRESS("computer_use.progress"),
  /** SUBAGENT開始。 */
  SUBAGENT_STARTED("subagent.started"),
  /** SUBAGENT完了。 */
  SUBAGENT_COMPLETED("subagent.completed"),
  /** SUBAGENT失敗。 */
  SUBAGENT_FAILED("subagent.failed"),
  /** 実行開始。 */
  EXECUTION_STARTED("execution.started"),
  /** 実行完了。 */
  EXECUTION_COMPLETED("execution.completed"),
  /** 実行失敗。 */
  EXECUTION_FAILED("execution.failed"),
  /** 実行キャンセル済み。 */
  EXECUTION_CANCELLED("execution.cancelled"),
  /** 履歴検索完了。 */
  HISTORY_SEARCH_COMPLETED("history.search.completed"),
  /** 利用者追加入力受信。 */
  USER_INTERVENTION_RECEIVED("user.intervention.received"),
  /** 利用者追加入力反映済みの内容。 */
  USER_INTERVENTION_APPLIED("user.intervention.applied"),
  /** 進捗検出した。 */
  PROGRESS_DETECTED("progress.detected"),
  /** 停滞更新。 */
  STAGNATION_UPDATED("stagnation.updated"),
  /** 停滞検出した。 */
  STAGNATION_DETECTED("stagnation.detected"),
  /** 停滞計画の見直し要求された。 */
  STAGNATION_REPLAN_REQUESTED("stagnation.replan_requested"),
  /** 停滞RECOVERED。 */
  STAGNATION_RECOVERED("stagnation.recovered"),
  /** 停滞停止済み。 */
  STAGNATION_STOPPED("stagnation.stopped"),
  /** エージェント実行開始。 */
  AGENT_RUN_STARTED("agent.run.started"),
  /** エージェント実行キャンセル済み。 */
  AGENT_RUN_CANCELLED("agent.run.cancelled"),
  /** エージェント実行完了。 */
  AGENT_RUN_COMPLETED("agent.run.completed"),
  /** エージェント実行失敗。 */
  AGENT_RUN_FAILED("agent.run.failed"),
  /** 言語モデル要求開始。 */
  LLM_REQUEST_STARTED("llm.request.started"),
  /** 言語モデル要求失敗。 */
  LLM_REQUEST_FAILED("llm.request.failed"),
  /** 言語モデル応答最初トークン。 */
  LLM_RESPONSE_FIRST_TOKEN("llm.response.first_token"),
  /** 言語モデル応答完了。 */
  LLM_RESPONSE_COMPLETED("llm.response.completed"),
  /** メッセージ開始。 */
  MESSAGE_STARTED("message.started"),
  /** メッセージ差分。 */
  MESSAGE_DELTA("message.delta"),
  /** メッセージ完了。 */
  MESSAGE_COMPLETED("message.completed"),
  /** 記憶統合SUGGESTED。 */
  MEMORY_CONSOLIDATION_SUGGESTED("memory.consolidation.suggested"),
  /** 推論開始。 */
  THINKING_STARTED("thinking.started"),
  /** 推論差分。 */
  THINKING_DELTA("thinking.delta"),
  /** 推論完了。 */
  THINKING_COMPLETED("thinking.completed"),
  /** ツール開始。 */
  TOOL_STARTED("tool.started"),
  /** ツールPLANNED。 */
  TOOL_PLANNED("tool.planned"),
  /** ツール完了。 */
  TOOL_COMPLETED("tool.completed"),
  /** ツール失敗。 */
  TOOL_FAILED("tool.failed"),
  /** スキル選択内容開始。 */
  SKILL_SELECTION_STARTED("skill.selection.started"),
  /** スキル選択内容完了。 */
  SKILL_SELECTION_COMPLETED("skill.selection.completed"),
  /** スキル選択内容失敗。 */
  SKILL_SELECTION_FAILED("skill.selection.failed"),
  /** スキル振り分け開始。 */
  SKILL_ROUTING_STARTED("skill.routing.started"),
  /** スキル振り分け完了。 */
  SKILL_ROUTING_COMPLETED("skill.routing.completed"),
  /** スキル振り分け失敗。 */
  SKILL_ROUTING_FAILED("skill.routing.failed"),
  /** スキル候補一覧評価済み。 */
  SKILL_CANDIDATES_EVALUATED("skill.candidates.evaluated"),
  /** タスク作成。 */
  TASK_CREATED("task.created"),
  /** タスク開始。 */
  TASK_STARTED("task.started"),
  /** タスク完了。 */
  TASK_COMPLETED("task.completed"),
  /** タスク失敗。 */
  TASK_FAILED("task.failed"),
  /** 作業中集合項目追加済み。 */
  WORKING_SET_ITEM_ADDED("working_set.item.added"),
  /** 作業中集合項目削除済み。 */
  WORKING_SET_ITEM_REMOVED("working_set.item.removed"),
  /** 作業中集合検索開始。 */
  WORKING_SET_SEARCH_STARTED("working_set.search.started"),
  /** 作業中集合検索完了。 */
  WORKING_SET_SEARCH_COMPLETED("working_set.search.completed"),
  /** 作業中集合文脈追加された。 */
  WORKING_SET_CONTEXT_INJECTED("working_set.context.injected"),
  /** 文脈追加された。 */
  CONTEXT_INJECTED("context.injected"),
  /** 文脈に使える予算評価済み。 */
  CONTEXT_BUDGET_EVALUATED("context.budget.evaluated"),
  /** 文脈に使える予算余白を除いた。 */
  CONTEXT_BUDGET_TRIMMED("context.budget.trimmed"),
  /** ファイル要約保存済み。 */
  FILE_SUMMARY_SAVED("file_summary.saved"),
  /** ファイル要約INVALIDATED。 */
  FILE_SUMMARY_INVALIDATED("file_summary.invalidated"),
  /** ファイル要約古い状態対象外。 */
  FILE_SUMMARY_STALE_SKIPPED("file_summary.stale_skipped"),
  /** 再開用の記録保存済み。 */
  CHECKPOINT_SAVED("checkpoint.saved"),
  /** 再開用の記録改訂番号保存済み。 */
  CHECKPOINT_REVISION_SAVED("checkpoint.revision.saved"),
  /** 再開用の記録RECONCILED。 */
  CHECKPOINT_RECONCILED("checkpoint.reconciled"),
  /** 再開用の記録再開済み。 */
  CHECKPOINT_RESUMED("checkpoint.resumed"),
  /** バックグラウンドプロセス開始。 */
  BACKGROUND_PROCESS_STARTED("background_process.started"),
  /** バックグラウンドプロセス完了。 */
  BACKGROUND_PROCESS_COMPLETED("background_process.completed"),
  /** バックグラウンドプロセス失敗。 */
  BACKGROUND_PROCESS_FAILED("background_process.failed"),
  /** バックグラウンドプロセス強制終了済み。 */
  BACKGROUND_PROCESS_KILLED("background_process.killed"),
  /** 話題生成開始。 */
  TOPIC_GENERATION_STARTED("topic.generation.started"),
  /** 話題操作がない状態実行条件評価済み。 */
  TOPIC_IDLE_TRIGGER_EVALUATED("topic.idle_trigger.evaluated"),
  /** 話題候補一覧REFRESHED。 */
  TOPIC_CANDIDATES_REFRESHED("topic.candidates.refreshed"),
  /** 話題候補生成済み。 */
  TOPIC_CANDIDATE_GENERATED("topic.candidate.generated"),
  /** 話題候補評価済み。 */
  TOPIC_CANDIDATE_SCORED("topic.candidate.scored"),
  /** 話題候補拒否済み。 */
  TOPIC_CANDIDATE_REJECTED("topic.candidate.rejected"),
  /** 話題選択中。 */
  TOPIC_SELECTED("topic.selected"),
  /** 話題読み上げ済み。 */
  TOPIC_SPOKEN("topic.spoken"),
  /** 話題読み上げ対象外。 */
  TOPIC_SPEAK_SKIPPED("topic.speak.skipped"),
  /** 話題自動読み上げ抑制された。 */
  TOPIC_AUTO_SPEAK_SUPPRESSED("topic.auto_speak.suppressed"),
  /** 話題生成完了。 */
  TOPIC_GENERATION_COMPLETED("topic.generation.completed"),
  /** 話題生成失敗。 */
  TOPIC_GENERATION_FAILED("topic.generation.failed"),
  /** 文脈状態の記録更新。 */
  CONTEXT_SNAPSHOT_UPDATED("context.snapshot.updated"),
  /** ファイル作成。 */
  FILE_CREATED("file.created"),
  /** ファイル更新。 */
  FILE_MODIFIED("file.modified"),
  /** ファイル削除済み。 */
  FILE_DELETED("file.deleted"),
  /** 作業文脈更新開始。 */
  WORK_CONTEXT_UPDATE_STARTED("work_context.update.started"),
  /** 作業文脈更新。 */
  WORK_CONTEXT_UPDATED("work_context.updated"),
  /** 作業文脈更新失敗。 */
  WORK_CONTEXT_UPDATE_FAILED("work_context.update.failed");

  /** 値。 */
  private final String value;

  /** 指定された値や利用先を使って、AgentEventTypeを初期化する。 */
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
