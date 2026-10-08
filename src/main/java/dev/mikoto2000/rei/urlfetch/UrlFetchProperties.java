package dev.mikoto2000.rei.urlfetch;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "rei.url-fetch")
public class UrlFetchProperties {
  private int maxWireBytes = 2 * 1024 * 1024;
  private int maxDecodedBytes = 4 * 1024 * 1024;
  private int connectTimeoutSeconds = 5;
  private int readTimeoutSeconds = 10;
  private int timeoutSeconds = 30;
  private int maxRedirects = 5;
  public dev.mikoto2000.rei.http.HttpFetchPolicy fetchPolicy() {
    return new dev.mikoto2000.rei.http.HttpFetchPolicy(maxWireBytes, maxDecodedBytes,
        java.time.Duration.ofSeconds(connectTimeoutSeconds), java.time.Duration.ofSeconds(readTimeoutSeconds),
        java.time.Duration.ofSeconds(timeoutSeconds), maxRedirects, null, null, false);
  }
}
