package dev.mikoto2000.rei.llm;

import org.springframework.ai.model.tool.ToolCallingChatOptions;

/** Wire options boundary. Execution, approval, persistence and Run state remain outside it. */
interface LlmProtocolAdapter {
  ToolCallingChatOptions options(ResolvedLlmEndpoint endpoint);
}
