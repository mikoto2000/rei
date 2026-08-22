package dev.mikoto2000.rei.event;

/**
 * ファイルの変更。
 *
 * <p>diff 全文はイベントに入れない。追加・削除行数など容易に取得できる要約のみを含める。</p>
 *
 * @param path ファイルのパス
 * @param addedLines 追加された行数（任意）
 * @param removedLines 削除された行数（任意）
 */
public record FileModifiedPayload(String path, Integer addedLines, Integer removedLines)
    implements AgentEventPayload {
}
