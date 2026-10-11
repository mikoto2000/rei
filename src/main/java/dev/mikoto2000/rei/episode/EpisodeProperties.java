package dev.mikoto2000.rei.episode;
@org.springframework.boot.context.properties.ConfigurationProperties("rei.memory.episodes")
public record EpisodeProperties(boolean enabled,boolean denseEnabled) {
 @org.springframework.boot.context.properties.bind.ConstructorBinding
 public EpisodeProperties {}
 public EpisodeProperties(boolean enabled){this(enabled,false);}
}
