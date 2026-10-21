package dev.mikoto2000.rei.temporal;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

/** 時刻と期間の解釈に必要な実行環境文脈対話の補助を扱う。 */
@Component
public class RuntimeContextAdvisor implements BaseAdvisor {
  /** 期限や記録日時の判定に使う時刻の取得元。 */
  private final Clock clock;

  /** 指定された値や利用先を使って、RuntimeContextAdvisorを初期化する。 */
  public RuntimeContextAdvisor(Clock clock) {
    this.clock = clock;
  }

  /** 時刻と期間の解釈の本処理へ渡す文脈を準備する。 */
  @Override
  public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
    UserMessage userMessage = request.prompt().getUserMessage();
    if (userMessage == null) {
      return request;
    }
    UserMessage contextualMessage =
        userMessage.mutate().text(renderContext() + "\n\n" + userMessage.getText()).build();
    Prompt prompt = request.prompt();
    return request
        .mutate()
        .prompt(
            new Prompt(
                replaceUserMessage(prompt.getInstructions(), userMessage, contextualMessage),
                prompt.getOptions()))
        .build();
  }

  /** 時刻と期間の解釈の実行結果を受け取り、後続の処理へ反映する。 */
  @Override
  public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
    return response;
  }

  /** 他の補助処理と組み合わせる際の適用順序を返す。 */
  @Override
  public int getOrder() {
    return -100;
  }

  /** 利用者が内容を確認できるよう、文脈を表示する。 */
  private String renderContext() {
    ZonedDateTime now = ZonedDateTime.now(clock);
    return """
        # Runtime Context

        Current time: %s
        Timezone: %s
        """
        .formatted(now.toOffsetDateTime(), now.getZone().getId())
        .stripTrailing();
  }

  /** 指定された内容へ切り替えるため、利用者メッセージを置き換える。 */
  private List<Message> replaceUserMessage(
      List<Message> messages, UserMessage original, UserMessage replacement) {
    List<Message> replaced = new ArrayList<>(messages.size());
    boolean replacedFirst = false;
    for (Message message : messages) {
      if (!replacedFirst && message == original) {
        replaced.add(replacement);
        replacedFirst = true;
      } else {
        replaced.add(message);
      }
    }
    return List.copyOf(replaced);
  }
}
