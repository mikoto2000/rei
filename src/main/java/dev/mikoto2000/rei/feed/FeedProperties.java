package dev.mikoto2000.rei.feed;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "rei.feed")
public record FeedProperties(int briefingMaxItems, List<Authentication> authentication) {

  @ConstructorBinding
  public FeedProperties {
    if (briefingMaxItems <= 0) briefingMaxItems = 3;
    authentication = authentication == null ? List.of() : List.copyOf(authentication);
  }

  public FeedProperties(int briefingMaxItems) { this(briefingMaxItems, List.of()); }
  public FeedProperties() { this(3); }

  /** Only references belong here, never the token itself. */
  public record Authentication(String url, String allowedOrigin, String tokenEnv) {
    @Override
    public String toString() { return "Authentication[configuration redacted]"; }
  }
}
