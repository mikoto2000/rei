package dev.mikoto2000.rei.bluesky;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BlueskyPostService {

  private static final Logger log = LoggerFactory.getLogger(BlueskyPostService.class);

  private final BlueskyProperties properties;
  private final BlueskyApiClient blueskyApiClient;

  public BlueskyPostResult post(String text) {
    try {
      log.debug("Bluesky post requested: textLength={}", text == null ? null : text.length());
      if (!properties.isEnabled()) {
        log.debug("Bluesky post skipped: feature disabled");
        return new BlueskyPostResult(false, "Bluesky posting is disabled", null, null);
      }
      if (isBlank(properties.getHandle()) || isBlank(properties.getAppPassword())) {
        log.debug("Bluesky post skipped: credentials missing (handleBlank={}, appPasswordBlank={})",
            isBlank(properties.getHandle()), isBlank(properties.getAppPassword()));
        return new BlueskyPostResult(false, "Bluesky credentials are not configured", null, null);
      }
      if (isBlank(text)) {
        log.debug("Bluesky post skipped: text is blank");
        return new BlueskyPostResult(false, "Post text must not be blank", null, null);
      }
      if (text.length() > properties.getMaxPostLength()) {
        log.debug("Bluesky post skipped: text too long (length={}, max={})", text.length(), properties.getMaxPostLength());
        return new BlueskyPostResult(false, "Post text exceeds max length: " + properties.getMaxPostLength(), null, null);
      }

      log.debug("Bluesky authenticating: handle={}", properties.getHandle());
      BlueskyApiClient.AuthResult authResult = blueskyApiClient.authenticate(properties.getHandle(), properties.getAppPassword());
      log.debug("Bluesky authenticate result: success={}, hasAccessJwt={}, did={}",
          authResult.success(), authResult.accessJwt() != null, authResult.did());
      if (!authResult.success()) {
        return new BlueskyPostResult(false, "Bluesky authentication failed", null, null);
      }

      BlueskyApiClient.PostResult postResult = blueskyApiClient.createPost(authResult.accessJwt(), authResult.did(), text);
      log.debug("Bluesky createPost result: success={}, postUri={}", postResult.success(), postResult.postUri());
      if (!postResult.success()) {
        return new BlueskyPostResult(false, "Bluesky post failed", null, null);
      }

      String postUrl = toPostUrl(postResult.postUri());
      return new BlueskyPostResult(true, "Bluesky post created", postResult.postUri(), postUrl);
    } catch (Exception e) {
      log.warn("Bluesky post failed due to unexpected error: {}", e.getMessage(), e);
      return new BlueskyPostResult(false, "Bluesky post failed due to unexpected error", null, null);
    }
  }

  private String toPostUrl(String postUri) {
    if (postUri == null || postUri.isBlank()) {
      return null;
    }
    String[] parts = postUri.split("/");
    if (parts.length < 3) {
      return null;
    }
    String did = parts[2];
    String rkey = parts[parts.length - 1];
    return "https://bsky.app/profile/" + did + "/post/" + rkey;
  }

  private boolean isBlank(String text) {
    return text == null || text.isBlank();
  }
}
