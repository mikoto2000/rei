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

  @Tool(name = "blueskyPost", description = """
    Bluesky にメッセージを投稿する。
    @param text 投稿するテキスト(必須)
    @return 投稿の結果。成功した場合は投稿のURLを含むメッセージ、失敗した場合はエラーメッセージ。
    """)
  public String post(String text) {
    log.debug("blueskyPost tool called. textLength={}", text == null ? null : text.length());
    BlueskyPostResult result = blueskyPostService.post(text);
    log.debug("blueskyPost tool result. success={}, message={}", result.success(), result.message());
    if (!result.success()) {
      return result.message();
    }
    return result.message() + "\npostUri: " + result.postUri() + "\npostUrl: " + result.postUrl();
  }
}
