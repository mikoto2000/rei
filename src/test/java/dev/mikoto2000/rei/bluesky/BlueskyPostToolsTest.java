package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class BlueskyPostToolsTest {

  @Test
  void postDelegatesToService() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.post("hello"))
        .thenReturn(new BlueskyPostResult(true, "Bluesky post created", "at://a/b/c", "https://bsky.app/profile/a/post/c"));
    BlueskyPostTools tools = new BlueskyPostTools(service);

    tools.post("hello");

    verify(service).post("hello");
  }

  @Test
  void postReturnsMessageOnError() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.post("hello"))
        .thenReturn(new BlueskyPostResult(false, "Bluesky post failed", null, null));
    BlueskyPostTools tools = new BlueskyPostTools(service);

    String result = tools.post("hello");

    assertTrue(result.contains("Bluesky post failed"));
  }

  @Test
  void postReturnsUriAndUrlOnSuccess() {
    BlueskyPostService service = mock(BlueskyPostService.class);
    when(service.post("hello"))
        .thenReturn(new BlueskyPostResult(true, "Bluesky post created", "at://a/b/c", "https://bsky.app/profile/a/post/c"));
    BlueskyPostTools tools = new BlueskyPostTools(service);

    String result = tools.post("hello");

    assertTrue(result.contains("Bluesky post created"));
    assertTrue(result.contains("postUri: at://a/b/c"));
    assertTrue(result.contains("postUrl: https://bsky.app/profile/a/post/c"));
  }
}
