package dev.mikoto2000.rei.ui.projection;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;

/** 実行状況の表示用整理に必要なエージェント表示表示用の状態を扱う。 */
public interface AgentUiProjection extends AgentEventListener {

  /** 指定された内容を、現在の対象へ反映する。 */
  void apply(AgentEvent event);

  /** 実行状況の表示用整理で用いる現在状態を返す。 */
  AgentUiState currentState();

  /** 通知に応じて状態や表示を更新するため、イベントを受け付ける。 */
  @Override
  default void onEvent(AgentEvent event) {
    apply(event);
  }
}
