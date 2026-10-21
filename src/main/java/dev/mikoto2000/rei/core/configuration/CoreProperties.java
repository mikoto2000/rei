package dev.mikoto2000.rei.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 機能の組み立てで受け渡すCore設定を表す。
 *
 * @param systemPrompt システムへの指示
 * @param chatMemoryMaxMessages チャット記憶最大メッセージ一覧
 */
@ConfigurationProperties(prefix = "rei.core")
public record CoreProperties(String systemPrompt, Integer chatMemoryMaxMessages) {}
