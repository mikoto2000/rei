package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

/** Bluesky投稿ツール一覧の結果と状態の契約を検証するテスト。 */
class BlueskyPostToolsTest {

  /** Blueskyとの連携で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void postDelegatesToService() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.post("hello"))
        .thenReturn(
            new BlueskyPostResult(
                true, "Bluesky post created", "at://a/b/c", "https://bsky.app/profile/a/post/c"));
    BlueskyPostTools tools = new BlueskyPostTools(service);

    tools.post("hello");

    verify(service).post("hello");
  }

  /** 投稿について、メッセージを返すことを確認する。 */
  @Test
  void postReturnsMessageOnError() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.post("hello"))
        .thenReturn(new BlueskyPostResult(false, "Bluesky post failed", null, null));
    BlueskyPostTools tools = new BlueskyPostTools(service);

    String result = tools.post("hello");

    assertTrue(result.contains("Bluesky post failed"));
  }

  /** 投稿について、参照先のURIを返すことを確認する。 */
  @Test
  void postReturnsUriAndUrlOnSuccess() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.post("hello"))
        .thenReturn(
            new BlueskyPostResult(
                true, "Bluesky post created", "at://a/b/c", "https://bsky.app/profile/a/post/c"));
    BlueskyPostTools tools = new BlueskyPostTools(service);

    String result = tools.post("hello");

    assertTrue(result.contains("Bluesky post created"));
    assertTrue(result.contains("postUri: at://a/b/c"));
    assertTrue(result.contains("postUrl: https://bsky.app/profile/a/post/c"));
  }
}
