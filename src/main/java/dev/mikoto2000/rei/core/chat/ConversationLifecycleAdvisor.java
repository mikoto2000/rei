package dev.mikoto2000.rei.core.chat;

import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import java.util.ArrayList;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * 会話の実行に必要な会話受付から終了までの管理対話の補助を扱う。
 *
 * <p>Render lifecycle after memory and working context assembly; never persist it as a user
 * request.
 */
public final class ConversationLifecycleAdvisor implements BaseAdvisor {
  /** やり取り一覧。 */
  private final ConversationTurnStore turns;

  /** 会話の識別子。 */
  private final String conversationId;

  /** 指定された値や利用先を使って、ConversationLifecycleAdvisorを初期化する。 */
  public ConversationLifecycleAdvisor(ConversationTurnStore turns, String conversationId) {
    this.turns = turns;
    this.conversationId = conversationId;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return 100;
  }

  /** 会話の実行の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    String context = turns.cancelledContext(conversationId);
    if (context.isEmpty()) return request;
    var messages = new ArrayList<Message>();
    var originalSystem = request.prompt().getSystemMessage();
    String system = originalSystem.getText();
    messages.add(new SystemMessage(system + "\n\n" + context));
    request.prompt().getInstructions().stream()
        .filter(m -> m != originalSystem)
        .forEach(messages::add);
    return request.mutate().prompt(new Prompt(messages, request.prompt().getOptions())).build();
  }

  /** 会話の実行の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }
}
