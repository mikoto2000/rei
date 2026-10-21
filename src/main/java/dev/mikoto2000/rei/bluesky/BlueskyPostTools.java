package dev.mikoto2000.rei.bluesky;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/** Bluesky投稿をモデルから呼び出せるツールとして提供する。 */
@Component
@RequiredArgsConstructor
public class BlueskyPostTools {
  /** 処理状況と問題の調査に使うログの出力先。 */
  private static final Logger log = LoggerFactory.getLogger(BlueskyPostTools.class);

  /** Bluesky投稿サービス。 */
  private final BlueskyPostService blueskyPostService;

  /** Blueskyとの連携で用いる投稿を返す。 */
  @Tool(
      name = "blueskyPost",
      description =
          """
    Bluesky にメッセージを投稿する。
    @param text 投稿するテキスト(必須)
    @return 投稿の結果。成功した場合は投稿のURLを含むメッセージ、失敗した場合はエラーメッセージ。
    """)
  public String post(String text) {
    log.debug("blueskyPost tool called. textLength={}", text == null ? null : text.length());
    BlueskyPostResult result = blueskyPostService.post(text);
    log.debug(
        "blueskyPost tool result. success={}, message={}", result.success(), result.message());
    if (!result.success()) {
      return result.message();
    }
    return result.message() + "\npostUri: " + result.postUri() + "\npostUrl: " + result.postUrl();
  }
}
