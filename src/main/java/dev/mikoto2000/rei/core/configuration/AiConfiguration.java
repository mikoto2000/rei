package dev.mikoto2000.rei.core.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.core.Tools;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarProperties;
import dev.mikoto2000.rei.googlecalendar.GoogleCalendarTools;
import dev.mikoto2000.rei.task.TaskTools;
import lombok.RequiredArgsConstructor;

/**
 * AiConfiguration
 */
@Configuration
@EnableConfigurationProperties(GoogleCalendarProperties.class)
@RequiredArgsConstructor
public class AiConfiguration {

  private final ChatModel chatModel;

  private final ChatMemory chatMemory;

  private final Tools tools;

  private final SimpleVectorStore vectorStore;

  private final GoogleCalendarTools googleCalendarTools;

  private final TaskTools taskTools;

  @Bean
  public ChatClient chatClient() {
    return ChatClient.builder(chatModel)
      .defaultSystem("""
          あなたは優秀なアシスタントです。
        ユーザーの質問に対して、必要に応じてツールを使いながら、正確かつ簡潔に答えてください。
        もし質問の意図が不明な場合は、ユーザーに質問の意図を確認してください。
        ファイルが見つからない場合は、 findFile ツールを使ってファイルを検索してください。
        ファイルにテキストデータを書き込む場合は、ツールの writeTextFile を使ってください。
        vectorStore に関する質問があった場合は、 QuestionAnswerAdvisor を使って vectorStore に保存された情報をもとに回答してください。
        タスク管理が必要な場合は taskList、taskCreate、taskComplete、taskUpdateDeadline を使ってください。
        """)
      .defaultAdvisors(
          MessageChatMemoryAdvisor.builder(chatMemory).build(),
          QuestionAnswerAdvisor.builder(vectorStore).build()
          )
      .defaultTools(tools, googleCalendarTools, taskTools)
      .build();
  }
}
