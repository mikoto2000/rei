package dev.mikoto2000.rei.ui.projection;

import dev.mikoto2000.rei.event.AgentEvent;
import dev.mikoto2000.rei.event.AgentEventListener;

public interface AgentUiProjection extends AgentEventListener {

  void apply(AgentEvent event);

  AgentUiState currentState();

  @Override
  default void onEvent(AgentEvent event) {
    apply(event);
  }
}
