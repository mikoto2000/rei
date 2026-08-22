package dev.mikoto2000.rei.event;

/**
 * ファイルの作成。
 *
 * @param path ファイルのパス
 */
public record FileCreatedPayload(String path)
    implements AgentEventPayload {
}
