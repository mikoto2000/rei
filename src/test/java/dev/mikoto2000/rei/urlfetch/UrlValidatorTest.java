package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** URL検証の窓口の結果と状態の契約を検証するテスト。 */
class UrlValidatorTest {

  /** 検証に使う検証の窓口。 */
  private final UrlValidator validator = new UrlValidator();

  /** 未指定のURLを受け付けないことを確認する。 */
  @Test
  void rejectsNullUrl() {
    UrlContentFetchResult result = validator.validate(null);
    assertFalse(result.success());
  }

  /** 空白のURLを受け付けないことを確認する。 */
  @Test
  void rejectsBlankUrl() {
    UrlContentFetchResult result = validator.validate("   ");
    assertFalse(result.success());
  }

  /** 以外HTTP通信通信方式を受け付けないことを確認する。 */
  @Test
  void rejectsNonHttpScheme() {
    UrlContentFetchResult result = validator.validate("ftp://example.com");
    assertFalse(result.success());
  }

  /** 形式が不正なURLを受け付けないことを確認する。 */
  @Test
  void rejectsMalformedUrl() {
    UrlContentFetchResult result = validator.validate("http://exa mple.com");
    assertFalse(result.success());
  }

  /** HTTP通信を受け付けられることを確認する。 */
  @Test
  void acceptsHttpAndHttps() {
    assertTrue(validator.validate("http://example.com").success());
    assertTrue(validator.validate("https://example.com").success());
  }
}
