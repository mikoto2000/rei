package dev.mikoto2000.rei.temporal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 時刻と期間の解釈で受け渡すエージェント実行予定の管理設定を表す。
 *
 * @param enabled 有効状態
 */
@ConfigurationProperties("rei.agent-scheduler")
public record AgentSchedulerProperties(boolean enabled) {}
