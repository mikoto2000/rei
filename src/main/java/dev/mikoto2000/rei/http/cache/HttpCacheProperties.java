package dev.mikoto2000.rei.http.cache;

@org.springframework.stereotype.Component
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "rei.http-cache")
@lombok.Getter @lombok.Setter
public class HttpCacheProperties {
  private boolean enabled = true;
  private int searchTtlSeconds = 30;
  private int pageTtlSeconds = 60;
  private int retentionSeconds = 300;
  private int maxEntries = 128;
  private long maxBytes = 32L * 1024 * 1024;
  private int loadParallelism = 3;
  private int loadQueueCapacity = 16;
  private int maxInFlight = 32;
  public void validate() {
    if (searchTtlSeconds < 1 || searchTtlSeconds > 300 || pageTtlSeconds < 1 || pageTtlSeconds > 300
        || retentionSeconds < Math.max(searchTtlSeconds, pageTtlSeconds) || retentionSeconds > 1800
        || maxEntries < 1 || maxEntries > 1024 || maxBytes < 1 || maxBytes > 128L * 1024 * 1024
        || loadParallelism < 1 || loadParallelism > 3 || loadQueueCapacity < 1 || loadQueueCapacity > 64
        || maxInFlight < 1 || maxInFlight > 128) throw new IllegalArgumentException("Invalid HTTP cache limits");
  }
}
