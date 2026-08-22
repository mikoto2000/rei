package dev.mikoto2000.rei.event;

/**
 * ファイルの削除。
 *
 * @param path ファイルのパス
 */
public record FileDeletedPayload(String path)
    implements AgentEventPayload {
}
