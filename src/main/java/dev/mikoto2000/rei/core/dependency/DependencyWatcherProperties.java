package dev.mikoto2000.rei.core.dependency;
@org.springframework.boot.context.properties.ConfigurationProperties("rei.dependency-watcher")
public record DependencyWatcherProperties(boolean enabled) {}
