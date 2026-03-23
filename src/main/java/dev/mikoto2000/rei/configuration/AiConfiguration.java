package dev.mikoto2000.rei.configuration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.mikoto2000.rei.Tools;
import lombok.RequiredArgsConstructor;

/**
 * AiConfiguration
 */
@Configuration
@RequiredArgsConstructor
public class AiConfiguration {

  private final ChatModel chatModel;

  private final ChatMemory chatMemory;

  private final Tools tools;

  @Bean
  public ChatClient chatClient() {
    return ChatClient.builder(chatModel)
      .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
      .defaultTools(tools)
      .build();
  }
}
