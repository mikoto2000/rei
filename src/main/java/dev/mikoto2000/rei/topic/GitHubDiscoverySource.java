package dev.mikoto2000.rei.topic;

import java.util.List;
import org.springframework.stereotype.Component;

/** 話題の管理に必要なGit連携先利用先の検出取得元を扱う。 */
@Component
public class GitHubDiscoverySource implements DiscoverySource {
  /** 話題の管理で用いる利用先の検出を返す。 */
  @Override
  public List<DiscoveryItem> discover(DiscoveryContext context) {
    return List.of();
  }
}
