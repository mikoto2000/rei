package dev.mikoto2000.rei.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.stereotype.Service;

import dev.mikoto2000.rei.core.datasource.ReiPaths;

@Service
public class ExternalConfigFileService {

  private final Path workDirectory;

  public ExternalConfigFileService() {
    this(Path.of("").toAbsolutePath().normalize());
  }

  public ExternalConfigFileService(Path workDirectory) {
    this.workDirectory = workDirectory;
  }

  public Path configFilePath() {
    return ReiPaths.configFilePath(workDirectory);
  }

  public Path initializeConfigFile(boolean force) {
    Path configFile = configFilePath();
    try {
      ReiPaths.ensureParentDirectoryExists(configFile);
      if (!force && Files.exists(configFile)) {
        return configFile;
      }
      Files.writeString(configFile, template());
      return configFile;
    } catch (Exception e) {
      throw new IllegalStateException("外部設定ファイルの初期化に失敗しました", e);
    }
  }

  private String template() {
    return """
        spring:
          ai:
            openai:
              base-url: ${REI_OPENAI_BASE_URL:http://127.0.0.1:11434}
              api-key: ${REI_OPENAI_API_KEY:dummy-key}
              chat:
                options:
                  model: ${REI_OPENAI_CHAT_MODEL:qwen3.5:122b}
              embedding:
                options:
                  model: ${REI_OPENAI_EMBEDDING_MODEL:qwen3-embedding:8b}

        rei:
          web-search:
            enabled: true
            providers:
              - name: duckduckgo
                base-url: ${REI_WEB_SEARCH_DUCKDUCKGO_BASE_URL:https://html.duckduckgo.com/html/}
              - name: brave
                base-url: ${REI_WEB_SEARCH_BRAVE_BASE_URL:https://api.search.brave.com/res/v1/web/search}
                api-key: ${REI_WEB_SEARCH_BRAVE_API_KEY:}
          interest:
            enabled: true
          feed:
            briefing-max-items: ${REI_FEED_BRIEFING_MAX_ITEMS:3}
            cron: ${REI_FEED_CRON:0 0 4 * * *}
          google-calendar:
            enabled: false
        """;
  }
}
