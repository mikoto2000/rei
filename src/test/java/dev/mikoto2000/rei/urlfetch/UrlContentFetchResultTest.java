package dev.mikoto2000.rei.urlfetch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** URL内容取得結果の結果と状態の契約を検証するテスト。 */
class UrlContentFetchResultTest {

  /** URLの内容の参照で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void successFactoryBuildsSuccessResult() {
    UrlContentFetchResult result = UrlContentFetchResult.success("content");

    assertTrue(result.success());
    assertEquals("content", result.content());
  }

  /** URLの内容の参照で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void failureFactoryBuildsFailureResult() {
    UrlContentFetchResult result = UrlContentFetchResult.failure("INPUT_ERROR", "invalid");

    assertFalse(result.success());
    assertEquals("INPUT_ERROR", result.errorType());
    assertEquals("invalid", result.errorMessage());
  }
}
