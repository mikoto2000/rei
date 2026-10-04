package dev.mikoto2000.rei.feed;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class FeedUrlSafetyTest {
  @Test
  void rejectsUrlCredentialsWithSanitizedError() {
    for (String url : new String[] {"https://user:secret@feed.example/rss", "https://feed.example/rss?access_token=secret", "https://feed.example/rss?%54oKeN=secret", "https://feed.example/rss?API_KEY=secret", "https://feed.example/rss#secret", "https://user:secret@invalid host/rss"}) {
      var error = assertThrows(IllegalArgumentException.class, () -> FeedUrlSafety.parse(url));
      assertFalse(error.toString().contains("secret"));
      assertNull(error.getCause());
    }
  }

  @Test
  void permitsPublicFeedQueriesAndRejectsInvalidSchemes() {
    assertEquals("limit=30&cursor=page2", FeedUrlSafety.parse("https://feed.example/feed.xml?limit=30&cursor=page2").getRawQuery());
    assertThrows(IllegalArgumentException.class, () -> FeedUrlSafety.parse("file:///tmp/feed.xml"));
    assertThrows(IllegalArgumentException.class, () -> FeedUrlSafety.parse("relative.xml"));
  }
}
