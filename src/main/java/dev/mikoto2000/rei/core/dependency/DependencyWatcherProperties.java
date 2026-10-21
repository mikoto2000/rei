package dev.mikoto2000.rei.core.dependency;

/**
 * 外部条件の待機で受け渡す依存条件Watcher設定を表す。
 *
 * @param enabled 有効状態
 */
@org.springframework.boot.context.properties.ConfigurationProperties("rei.dependency-watcher")
public record DependencyWatcherProperties(boolean enabled) {}
