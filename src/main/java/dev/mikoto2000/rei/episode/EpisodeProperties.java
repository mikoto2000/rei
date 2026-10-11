package dev.mikoto2000.rei.episode;
@org.springframework.boot.context.properties.ConfigurationProperties("rei.memory.episodes")
public record EpisodeProperties(boolean enabled) {}
