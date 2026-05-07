package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UrlContentFetchResultTest {

  @Test
  void successFactoryBuildsSuccessResult() {
    UrlContentFetchResult result = UrlContentFetchResult.success("content");

    assertTrue(result.success());
    assertEquals("content", result.content());
  }

  @Test
  void failureFactoryBuildsFailureResult() {
    UrlContentFetchResult result = UrlContentFetchResult.failure("INPUT_ERROR", "invalid");

    assertFalse(result.success());
    assertEquals("INPUT_ERROR", result.errorType());
    assertEquals("invalid", result.errorMessage());
  }
}
