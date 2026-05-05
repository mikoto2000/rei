package dev.mikoto2000.rei.bluesky;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BlueskyPostTools {
  private static final Logger log = LoggerFactory.getLogger(BlueskyPostTools.class);

  private final BlueskyPostService blueskyPostService;

  @Tool(name = "blueskyPost", description = "Post text to Bluesky. Returns success/failure and URL when available.")
  public String post(String text) {
    log.info("blueskyPost tool called. textLength={}", text == null ? null : text.length());
    BlueskyPostResult result = blueskyPostService.post(text);
    log.info("blueskyPost tool result. success={}, message={}", result.success(), result.message());
    if (!result.success()) {
      return result.message();
    }
    return result.message() + "\npostUri: " + result.postUri() + "\npostUrl: " + result.postUrl();
  }
}
