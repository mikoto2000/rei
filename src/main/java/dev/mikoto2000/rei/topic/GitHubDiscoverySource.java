package dev.mikoto2000.rei.topic;

import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class GitHubDiscoverySource implements DiscoverySource {
  @Override
  public List<DiscoveryItem> discover(DiscoveryContext context) {
    return List.of();
  }
}
