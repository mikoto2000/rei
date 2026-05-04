package dev.mikoto2000.rei.bluesky;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class BlueskyPostTools {

  private final BlueskyPostService blueskyPostService;

  @Tool(name = "blueskyPost", description = "Post text to Bluesky. Returns success/failure and URL when available.")
  public String post(String text) {
    BlueskyPostResult result = blueskyPostService.post(text);
    if (!result.success()) {
      return result.message();
    }
    return result.message() + "\npostUri: " + result.postUri() + "\npostUrl: " + result.postUrl();
  }
}
