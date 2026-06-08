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
          openai:
            round-robin-enabled: ${REI_OPENAI_ROUND_ROBIN_ENABLED:false}
            base-urls: ${REI_OPENAI_BASE_URLS:}
            servers:
              # - base-url: http://127.0.0.1:11434
              #   chat-model: qwen3.5:122b
              #   embedding-model: qwen3-embedding:8b
              # - base-url: http://127.0.0.2:11434
              #   chat-model: qwen3.5:32b
              #   embedding-model: qwen3-embedding:8b
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
            notification-enabled: false
            notification-cron: ${REI_INTEREST_NOTIFICATION_CRON:0 0 12 * * *}
          feed:
            briefing-max-items: ${REI_FEED_BRIEFING_MAX_ITEMS:3}
            cron: ${REI_FEED_CRON:0 0 4 * * *}
          bluesky:
            enabled: false
            handle: ${REI_BLUESKY_HANDLE:}
            app-password: ${REI_BLUESKY_APP_PASSWORD:}
            max-post-length: ${REI_BLUESKY_MAX_POST_LENGTH:300}
            reply:
              enabled: false
              dry-run: true
              check-interval-seconds: ${REI_BLUESKY_REPLY_CHECK_INTERVAL_SECONDS:300}
              fetch-limit: ${REI_BLUESKY_REPLY_FETCH_LIMIT:30}
              exclude-replies: true
              exclude-reposts: true
              max-post-age-minutes: ${REI_BLUESKY_REPLY_MAX_POST_AGE_MINUTES:120}
              users:
                - handle: "alice.bsky.social"
                  probability: 0.25
                  max-replies-per-day: 3
          google:
            application-name: Rei
            credentials-path: ${REI_GOOGLE_CREDENTIALS_PATH:${user.dir}/.rei/google-calendar-credentials.json}
            tokens-directory: ${REI_GOOGLE_TOKENS_DIR:${user.dir}/.rei/google-calendar-tokens}
            calendar:
              enabled: false
              default-calendar-id: ${REI_GOOGLE_CALENDAR_DEFAULT_CALENDAR_ID:primary}
              time-zone: ${REI_GOOGLE_CALENDAR_TIME_ZONE:}
            task:
              enabled: true
        """;
  }
}
