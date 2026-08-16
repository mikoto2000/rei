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
              image:
                options:
                  model: ${REI_OPENAI_IMAGE_MODEL:gpt-image-1}
            model:
              chat: openai
              embedding: openai
              image: openai

        rei:
          llm:
            max-output-tokens: ${REI_LLM_MAX_OUTPUT_TOKENS:8192}
            output-limit:
              max-replans-per-goal: ${REI_LLM_OUTPUT_LIMIT_MAX_REPLANS_PER_GOAL:2}
              max-subgoals-per-replan: ${REI_LLM_OUTPUT_LIMIT_MAX_SUBGOALS_PER_REPLAN:8}
              max-llm-calls-per-run: ${REI_LLM_OUTPUT_LIMIT_MAX_LLM_CALLS_PER_RUN:30}
            features:
              chat:
                base-url: ${REI_LLM_CHAT_BASE_URL:}
                api-key: ${REI_LLM_CHAT_API_KEY:}
                model: ${REI_LLM_CHAT_MODEL:}
              search:
                base-url: ${REI_LLM_SEARCH_BASE_URL:}
                api-key: ${REI_LLM_SEARCH_API_KEY:}
                model: ${REI_LLM_SEARCH_MODEL:}
              memory:
                base-url: ${REI_LLM_MEMORY_BASE_URL:}
                api-key: ${REI_LLM_MEMORY_API_KEY:}
                model: ${REI_LLM_MEMORY_MODEL:}
              bluesky-reply:
                base-url: ${REI_LLM_BLUESKY_REPLY_BASE_URL:}
                api-key: ${REI_LLM_BLUESKY_REPLY_API_KEY:}
                model: ${REI_LLM_BLUESKY_REPLY_MODEL:}
              feed-summary:
                base-url: ${REI_LLM_FEED_SUMMARY_BASE_URL:}
                api-key: ${REI_LLM_FEED_SUMMARY_API_KEY:}
                model: ${REI_LLM_FEED_SUMMARY_MODEL:}
              briefing:
                base-url: ${REI_LLM_BRIEFING_BASE_URL:}
                api-key: ${REI_LLM_BRIEFING_API_KEY:}
                model: ${REI_LLM_BRIEFING_MODEL:}
              interest-discovery:
                base-url: ${REI_LLM_INTEREST_DISCOVERY_BASE_URL:}
                api-key: ${REI_LLM_INTEREST_DISCOVERY_API_KEY:}
                model: ${REI_LLM_INTEREST_DISCOVERY_MODEL:}
              agent-skills:
                base-url: ${REI_LLM_AGENT_SKILLS_BASE_URL:}
                api-key: ${REI_LLM_AGENT_SKILLS_API_KEY:}
                model: ${REI_LLM_AGENT_SKILLS_MODEL:}
              output-limit-planner:
                base-url: ${REI_LLM_OUTPUT_LIMIT_PLANNER_BASE_URL:}
                api-key: ${REI_LLM_OUTPUT_LIMIT_PLANNER_API_KEY:}
                model: ${REI_LLM_OUTPUT_LIMIT_PLANNER_MODEL:}
              image-prompt:
                base-url: ${REI_LLM_IMAGE_PROMPT_BASE_URL:}
                api-key: ${REI_LLM_IMAGE_PROMPT_API_KEY:}
                model: ${REI_LLM_IMAGE_PROMPT_MODEL:}
              image-generation:
                base-url: ${REI_LLM_IMAGE_GENERATION_BASE_URL:}
                api-key: ${REI_LLM_IMAGE_GENERATION_API_KEY:}
                model: ${REI_LLM_IMAGE_GENERATION_MODEL:}
          image:
            output-directory: ${REI_IMAGE_OUTPUT_DIRECTORY:${user.dir}/.rei/images}
            size: ${REI_IMAGE_SIZE:1024x1024}
            response-format: ${REI_IMAGE_RESPONSE_FORMAT:auto}
            timeout-seconds: ${REI_IMAGE_TIMEOUT_SECONDS:300}
            prompt-enhancement:
              enabled: ${REI_IMAGE_PROMPT_ENHANCEMENT_ENABLED:true}
          skills:
            enabled: true
            directories:
              - ${user.dir}/.rei/skills
            max-selected: 3
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
            timeout-seconds: ${REI_BLUESKY_TIMEOUT_SECONDS:30}
            reply:
              enabled: false
              dry-run: true
              check-interval-seconds: ${REI_BLUESKY_REPLY_CHECK_INTERVAL_SECONDS:300}
              fetch-limit: ${REI_BLUESKY_REPLY_FETCH_LIMIT:30}
              exclude-replies: true
              exclude-reposts: true
              max-post-age-minutes: ${REI_BLUESKY_REPLY_MAX_POST_AGE_MINUTES:120}
              generation-timeout-seconds: ${REI_BLUESKY_REPLY_GENERATION_TIMEOUT_SECONDS:1200}
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
