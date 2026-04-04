package dev.mikoto2000.rei.core.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@ConfigurationProperties(prefix = "rei.sqlite-vec")
public class SqliteVecProperties {

  private String version = "0.1.9";
  private boolean autoDownload = true;
  private String cacheDir = ".rei/extensions/sqlite-vec";
  private String extensionPath;
  private String releaseBaseUrl = "https://github.com/asg017/sqlite-vec/releases/download";
}
