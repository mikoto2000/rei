package dev.mikoto2000.rei.ui.projection;

import java.util.List;

/**
 * 実行状況の表示用整理で受け渡すエージェント表示状態を表す。
 *
 * @param run 実行
 * @param messages メッセージ一覧
 * @param tools ツール一覧
 * @param lastSequence 直近発生順序
 */
public record AgentUiState(
    AgentRunView run,
    List<MessageView> messages,
    List<ToolExecutionView> tools,
    long lastSequence) {

  /** 受け取った情報からAgentUiStateを作成する。 */
  public AgentUiState {
    messages = List.copyOf(messages);
    tools = List.copyOf(tools);
  }
}
