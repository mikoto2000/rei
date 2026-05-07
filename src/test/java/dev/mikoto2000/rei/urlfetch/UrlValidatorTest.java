package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class UrlValidatorTest {

  private final UrlValidator validator = new UrlValidator();

  @Test
  void rejectsNullUrl() {
    UrlContentFetchResult result = validator.validate(null);
    assertFalse(result.success());
  }

  @Test
  void rejectsBlankUrl() {
    UrlContentFetchResult result = validator.validate("   ");
    assertFalse(result.success());
  }

  @Test
  void rejectsNonHttpScheme() {
    UrlContentFetchResult result = validator.validate("ftp://example.com");
    assertFalse(result.success());
  }

  @Test
  void rejectsMalformedUrl() {
    UrlContentFetchResult result = validator.validate("http://exa mple.com");
    assertFalse(result.success());
  }

  @Test
  void acceptsHttpAndHttps() {
    assertTrue(validator.validate("http://example.com").success());
    assertTrue(validator.validate("https://example.com").success());
  }
}
