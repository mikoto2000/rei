package dev.mikoto2000.rei.bluesky;

public interface BlueskyApiClient {

  AuthResult authenticate(String handle, String appPassword);

  PostResult createPost(String accessJwt, String did, String text);

  record AuthResult(boolean success, String accessJwt, String did) {
  }

  record PostResult(boolean success, String postUri) {
  }
}
