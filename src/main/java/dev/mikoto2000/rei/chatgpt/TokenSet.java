package dev.mikoto2000.rei.chatgpt;

import java.util.Set;

/** Credentials never use the generated record toString. */
public record TokenSet(
    String clientId,
    String subject,
    String email,
    String idToken,
    String accessToken,
    String refreshToken,
    Set<String> scopes,
    long expiresAt) {
  public TokenSet {
    scopes = Set.copyOf(scopes);
  }

  public TokenSet withoutTokens() {
    return new TokenSet(clientId, subject, email, null, null, null, Set.of(), 0);
  }

  @Override
  public String toString() {
    return "TokenSet[protected]";
  }
}
