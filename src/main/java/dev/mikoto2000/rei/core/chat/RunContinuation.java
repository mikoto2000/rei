package dev.mikoto2000.rei.core.chat;

import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;
import java.util.List;
import java.util.Set;
import org.springframework.ai.chat.messages.Message;

/** Narrow asynchronous dependency seam at the existing completed-tool-batch boundary. */
public interface RunContinuation {
  record PendingTools(
      String text,
      List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls,
      List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> completed,
      Set<String> argumentCorrections) {
    public PendingTools {
      calls = List.copyOf(calls);
      completed = List.copyOf(completed);
      argumentCorrections =
          argumentCorrections == null ? Set.of() : Set.copyOf(argumentCorrections);
    }

    public PendingTools(
        String text,
        List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls,
        List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> completed) {
      this(text, calls, completed, Set.of());
    }
  }

  record Saved(
      String request,
      List<Message> messages,
      OutputLimitRunBudget.Snapshot budget,
      PendingTools pendingTools) {
    public Saved(String request, List<Message> messages, OutputLimitRunBudget.Snapshot budget) {
      this(request, messages, budget, null);
    }
  }

  Saved restore(AgentRunContext owner);

  boolean suspend(RunExecutionContext execution, List<Message> history);

  default void attachParent(
      AgentRunContext owner,
      OutputLimitRunBudget.LlmCallReservation reservation,
      java.util.function.Consumer<ChatExecutionResult> completed) {}

  default void detachParent(AgentRunContext owner) {}

  default void terminal(AgentRunContext owner) {}

  default void acceptGuidance(AgentRunContext owner, List<Message> guidance) {}
}
