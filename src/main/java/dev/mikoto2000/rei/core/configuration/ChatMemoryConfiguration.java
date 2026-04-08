package dev.mikoto2000.rei.core.configuration;

import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import lombok.RequiredArgsConstructor;

/**
 * ChatMemoryConfiguration
 */
@EnableConfigurationProperties({CoreProperties.class})
@RequiredArgsConstructor
public class ChatMemoryConfiguration {

  private final CoreProperties coreProperties;

  @Bean
  public MessageWindowChatMemory messageWindowChatMemory() {
    return MessageWindowChatMemory.builder()
        .maxMessages(coreProperties.chatMemoryMaxMessages())
        .build();
  }
}
