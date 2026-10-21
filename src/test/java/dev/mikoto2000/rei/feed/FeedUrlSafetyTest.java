package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** フィードURL安全性の結果と状態の契約を検証するテスト。 */
class FeedUrlSafetyTest {
  /** 認証情報を含むURLを拒否し、拒否理由からも秘密情報を参照できないことを確認する。 */
  @Test
  void rejectsUrlCredentialsWithSanitizedError() {
    for (String url :
        new String[] {
          "https://user:secret@feed.example/rss",
          "https://feed.example/rss?access_token=secret",
          "https://feed.example/rss?%54oKeN=secret",
          "https://feed.example/rss?API_KEY=secret",
          "https://feed.example/rss#secret",
          "https://user:secret@invalid host/rss"
        }) {
      var error = assertThrows(IllegalArgumentException.class, () -> FeedUrlSafety.parse(url));
      // 利用者へ返す失敗情報からも、URLに含まれた秘密情報を読み取れないことを保証する。
      assertFalse(error.toString().contains("secret"));
      assertNull(error.getCause());
    }
  }

  /** 公開フィードの検索引数を保ち、ファイル参照や相対URLを購読先として受け付けないことを確認する。 */
  @Test
  void permitsPublicFeedQueriesAndRejectsInvalidSchemes() {
    assertEquals(
        "limit=30&cursor=page2",
        FeedUrlSafety.parse("https://feed.example/feed.xml?limit=30&cursor=page2").getRawQuery());
    assertThrows(IllegalArgumentException.class, () -> FeedUrlSafety.parse("file:///tmp/feed.xml"));
    assertThrows(IllegalArgumentException.class, () -> FeedUrlSafety.parse("relative.xml"));
  }
}
