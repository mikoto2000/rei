package dev.mikoto2000.rei.core.configuration;

import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * チャット記憶の構成要素を接続し、設定に従って利用できるようにする。
 *
 * <p>ChatMemoryConfiguration
 */
@EnableConfigurationProperties({CoreProperties.class})
@RequiredArgsConstructor
public class ChatMemoryConfiguration {

  /** core設定。 */
  private final CoreProperties coreProperties;

  /** 機能の組み立てで用いるメッセージ範囲チャット記憶を返す。 */
  @Bean
  public MessageWindowChatMemory messageWindowChatMemory() {
    return MessageWindowChatMemory.builder()
        .maxMessages(coreProperties.chatMemoryMaxMessages())
        .build();
  }
}
