package dev.mikoto2000.rei.core;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 検索ファイル要求の結果と状態の契約を検証するテスト。 */
class FindFileRequestTest {

  /** 検索語句一覧を受け付けられることを確認する。 */
  @Test
  void acceptsKeywordsWithOptionalLimit() {
    assertDoesNotThrow(() -> new FindFileRequest(List.of("feed", "summary"), null));
    assertDoesNotThrow(() -> new FindFileRequest(List.of("feed"), 200));
  }

  /** 未指定検索語句一覧を受け付けないことを確認する。 */
  @Test
  void rejectsMissingKeywordsAndInvalidLimits() {
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of(), null));
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of(" "), null));
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of("feed"), 0));
    assertThrows(IllegalArgumentException.class, () -> new FindFileRequest(List.of("feed"), 201));
  }
}
