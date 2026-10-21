package dev.mikoto2000.rei.memory.service;

import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.chat.RunCancellation;
import java.util.ArrayList;
import java.util.Map;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/**
 * 長期記憶の保存と検索に必要な記憶文脈対話の補助を扱う。
 *
 * <p>Supplemental system message after history assembly; never written to history or rolling
 * summaries.
 */
@Component
public class MemoryContextAdvisor implements BaseAdvisor {
  /** 関連する記憶を検索する窓口。 */
  private final MemoryRetriever retriever;

  /** 指定された値や利用先を使って、MemoryContextAdvisorを初期化する。 */
  public MemoryContextAdvisor(MemoryRetriever retriever) {
    this.retriever = retriever;
  }

  /** 長期記憶の保存と検索の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    var owner = request.context().get(AgentRunContext.class.getName());
    if (!(owner instanceof AgentRunContext run)) return request;
    var users =
        request.prompt().getInstructions().stream()
            .filter(UserMessage.class::isInstance)
            .map(UserMessage.class::cast)
            .toList();
    if (users.isEmpty()) return request;
    try {
      var result = retriever.retrieve(users.getLast().getText(), run.projectId());
      if (result.context().isBlank()) return request;
      var messages = new ArrayList<Message>(request.prompt().getInstructions());
      messages.add(
          SystemMessage.builder()
              .text(result.context())
              .metadata(Map.of("rei.longTermMemory", true))
              .build());
      return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
    } catch (RuntimeException error) {
      RunCancellation.propagate(error);
      org.slf4j.LoggerFactory.getLogger(getClass())
          .warn(
              "Memory retrieval unavailable; continuing without memories ({})",
              error.getClass().getSimpleName());
      return request;
    }
  }

  /** 長期記憶の保存と検索の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -110;
  }
}
