package dev.mikoto2000.rei.core.contextbudget;

/**
 * LLM に渡す内部状態の 1 セクション。
 *
 * @param name    セクション名
 * @param content セクション内容
 */
public record ContextSection(String name, String content) {
}
