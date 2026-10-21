package dev.mikoto2000.rei.subagent;

/**
 * 補助エージェントへの委譲で受け渡す検証エラーを表す。
 *
 * <p>JSON Pointer and a value-free diagnostic, safe to return to the parent.
 *
 * @param path パス
 * @param message メッセージ
 */
public record ValidationError(String path, String message) {}
