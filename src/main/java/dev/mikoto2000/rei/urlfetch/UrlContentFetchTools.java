package dev.mikoto2000.rei.urlfetch;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class UrlContentFetchTools {

  private final UrlContentFetchService urlContentFetchService;

  @Tool(name = "fetchUrlContent", description = "指定した URL の内容を読み込みます。http/https のみ対応します。")
  public UrlContentFetchResult fetchUrlContent(String url) {
    return urlContentFetchService.fetch(url);
  }
}
