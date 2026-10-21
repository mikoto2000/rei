package dev.mikoto2000.rei.event;

import dev.mikoto2000.rei.github.GitHubFact;

/**
 * 実行状況の通知で受け渡すGit連携先受付から終了までの管理通知内容を表す。
 *
 * @param deliveryId 配信識別子
 * @param sourceId 取得元識別子
 * @param fact 事実
 */
public record GitHubLifecyclePayload(String deliveryId, String sourceId, GitHubFact fact)
    implements AgentEventPayload {}
