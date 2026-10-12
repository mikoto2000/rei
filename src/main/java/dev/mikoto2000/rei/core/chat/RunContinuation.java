package dev.mikoto2000.rei.core.chat;

import java.util.List;
import org.springframework.ai.chat.messages.Message;
import dev.mikoto2000.rei.core.stagnation.RunExecutionContext;
import dev.mikoto2000.rei.llm.OutputLimitRunBudget;

/** Narrow asynchronous dependency seam at the existing completed-tool-batch boundary. */
public interface RunContinuation {
  record PendingTools(String text,List<org.springframework.ai.chat.messages.AssistantMessage.ToolCall> calls,
      List<org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse> completed) {}
  record Saved(String request,List<Message> messages,OutputLimitRunBudget.Snapshot budget,PendingTools pendingTools) {
    public Saved(String request,List<Message> messages,OutputLimitRunBudget.Snapshot budget){this(request,messages,budget,null);}
  }
  Saved restore(AgentRunContext owner);
  boolean suspend(RunExecutionContext execution,List<Message> history);
  default void attachParent(AgentRunContext owner,OutputLimitRunBudget.LlmCallReservation reservation,java.util.function.Consumer<ChatExecutionResult> completed) {}
  default void detachParent(AgentRunContext owner) {}
  default void terminal(AgentRunContext owner) {}
  default void acceptGuidance(AgentRunContext owner,List<Message> guidance) {}
}
