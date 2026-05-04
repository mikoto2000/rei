package dev.mikoto2000.rei.bluesky;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class BlueskyPostServiceTest {

  @Test
  void returnsDisabledWhenFeatureOff() {
    BlueskyProperties props = configuredProperties();
    props.setEnabled(false);
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("hello");

    assertFalse(result.success());
    assertEquals("Bluesky posting is disabled", result.message());
    verify(client, never()).authenticate(props.getHandle(), props.getAppPassword());
  }

  @Test
  void returnsCredentialErrorWhenMissing() {
    BlueskyProperties props = configuredProperties();
    props.setHandle("");
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("hello");

    assertFalse(result.success());
    assertEquals("Bluesky credentials are not configured", result.message());
  }

  @Test
  void returnsValidationErrorWhenBlank() {
    BlueskyProperties props = configuredProperties();
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("   ");

    assertFalse(result.success());
    assertEquals("Post text must not be blank", result.message());
  }

  @Test
  void returnsValidationErrorWhenTooLong() {
    BlueskyProperties props = configuredProperties();
    props.setMaxPostLength(5);
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("123456");

    assertFalse(result.success());
    assertEquals("Post text exceeds max length: 5", result.message());
  }

  @Test
  void returnsAuthFailedWhenAuthenticateFails() {
    BlueskyProperties props = configuredProperties();
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    when(client.authenticate(props.getHandle(), props.getAppPassword()))
        .thenReturn(new BlueskyApiClient.AuthResult(false, null, null));
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("hello");

    assertFalse(result.success());
    assertEquals("Bluesky authentication failed", result.message());
  }

  @Test
  void returnsPostFailedWhenCreatePostFails() {
    BlueskyProperties props = configuredProperties();
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    when(client.authenticate(props.getHandle(), props.getAppPassword()))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:abc"));
    when(client.createPost("jwt", "did:plc:abc", "hello"))
        .thenReturn(new BlueskyApiClient.PostResult(false, null));
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("hello");

    assertFalse(result.success());
    assertEquals("Bluesky post failed", result.message());
  }

  @Test
  void returnsPostUriAndUrlWhenSuccess() {
    BlueskyProperties props = configuredProperties();
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    when(client.authenticate(props.getHandle(), props.getAppPassword()))
        .thenReturn(new BlueskyApiClient.AuthResult(true, "jwt", "did:plc:abc"));
    when(client.createPost("jwt", "did:plc:abc", "hello"))
        .thenReturn(new BlueskyApiClient.PostResult(true, "at://did:plc:abc/app.bsky.feed.post/3kxyz"));
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("hello");

    assertTrue(result.success());
    assertEquals("Bluesky post created", result.message());
    assertEquals("at://did:plc:abc/app.bsky.feed.post/3kxyz", result.postUri());
    assertEquals("https://bsky.app/profile/did:plc:abc/post/3kxyz", result.postUrl());
  }

  @Test
  void returnsUnexpectedErrorWhenExceptionThrown() {
    BlueskyProperties props = configuredProperties();
    BlueskyApiClient client = mock(BlueskyApiClient.class);
    when(client.authenticate(props.getHandle(), props.getAppPassword()))
        .thenThrow(new RuntimeException("network"));
    BlueskyPostService service = new BlueskyPostService(props, client);

    BlueskyPostResult result = service.post("hello");

    assertFalse(result.success());
    assertEquals("Bluesky post failed due to unexpected error", result.message());
  }

  private BlueskyProperties configuredProperties() {
    BlueskyProperties props = new BlueskyProperties();
    props.setEnabled(true);
    props.setHandle("test.bsky.social");
    props.setAppPassword("app-password");
    props.setMaxPostLength(300);
    return props;
  }
}
