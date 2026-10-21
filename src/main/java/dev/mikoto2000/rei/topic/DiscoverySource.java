package dev.mikoto2000.rei.topic;

import java.util.List;

/** 話題の管理に必要な利用先の検出取得元を扱う。 */
public interface DiscoverySource {
  /** 話題の管理で用いる利用先の検出を返す。 */
  List<DiscoveryItem> discover(DiscoveryContext context) throws Exception;
}
