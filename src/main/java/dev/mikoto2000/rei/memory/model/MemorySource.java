package dev.mikoto2000.rei.memory.model;

/**
 * 長期記憶の表現で受け渡す記憶取得元を表す。
 *
 * @param sessionId 会話の識別子
 * @param turnId やり取りの識別子
 */
public record MemorySource(String sessionId, String turnId) {}
