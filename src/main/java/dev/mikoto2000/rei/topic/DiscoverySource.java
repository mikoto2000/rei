package dev.mikoto2000.rei.topic;

import java.util.List;

public interface DiscoverySource {
  List<DiscoveryItem> discover(DiscoveryContext context) throws Exception;
}
