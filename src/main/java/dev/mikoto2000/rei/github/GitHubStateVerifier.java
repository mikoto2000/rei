package dev.mikoto2000.rei.github;
/** Optional read-only API verification before accepting effects. Unavailable is never confirmed. */
@FunctionalInterface
public interface GitHubStateVerifier {
  enum Verdict { CONFIRMED, REJECTED, UNAVAILABLE }
  Verdict verify(GitHubFact fact);
}
