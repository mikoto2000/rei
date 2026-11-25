package dev.mikoto2000.rei.llm;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiConnectionDetails;
import org.springframework.beans.factory.ObjectProvider;

/** Host-owned common connection selection, Run snapshots and inference resources. */
@org.springframework.stereotype.Component
public final class ChatConnectionService implements AutoCloseable {
  public ChatConnectionService(ChatEndpointCatalog catalog, LlmProperties properties,
      LlmModelProvider models, ModelHolderService holder,
      ObjectProvider<OpenAiConnectionDetails> details, ObjectProvider<OpenAiCommonProperties> common,
      ObjectProvider<OpenAiChatProperties> chat,
      ObjectProvider<dev.mikoto2000.rei.llm.capture.CaptureStore> captures) {}

  public RunLlmConnection accept(AgentRunContext owner) {
    throw new IllegalStateException("Chat connection service is unavailable");
  }

  public RunLlmConnection require(AgentRunContext owner) {
    throw new IllegalStateException("Chat connection service is unavailable");
  }

  public ToolCallingChatOptions options(AgentRunContext owner) {
    throw new IllegalStateException("Chat connection service is unavailable");
  }

  public void restore(AgentRunContext owner, RunLlmConnection saved) {
    throw new IllegalStateException("Chat connection service is unavailable");
  }

  public void release(AgentRunContext owner) {}
  public void select(String endpoint) {}
  public void selectModel(String model) {}

  public ChatModel model() {
    throw new IllegalStateException("Chat connection service is unavailable");
  }

  public ChatClient client(LlmChatClientProvider provider, AgentRunContext.Mode mode, RunLlmConnection connection) {
    throw new IllegalStateException("Chat connection service is unavailable");
  }

  @Override
  public void close() {}
}
