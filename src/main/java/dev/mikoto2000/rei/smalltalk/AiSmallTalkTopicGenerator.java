package dev.mikoto2000.rei.smalltalk;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class AiSmallTalkTopicGenerator implements SmallTalkTopicGenerator {

  private final ChatModel chatModel;
  private final ModelHolderService modelHolderService;

  @Override
  public String generate() {
    Prompt prompt = new Prompt(
        """
            あなたはユーザーに軽く話しかけるための雑談の話題を 1 つだけ日本語で提案する。
            条件:
            - 出力は 1 行だけ
            - 60 文字以内
            - 押しつけがましくしない
            - 会話メモリには保存されない前提なので、その場で気軽に返せる内容にする
            - あいさつや前置きは不要
            """,
        OpenAiChatOptions.builder()
            .model(modelHolderService.get())
            .build());

    return chatModel.call(prompt).getResult().getOutput().getText().trim();
  }
}
