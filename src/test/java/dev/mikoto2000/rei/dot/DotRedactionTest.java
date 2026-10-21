package dev.mikoto2000.rei.dot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 状態表示秘密情報の除去の結果と状態の契約を検証するテスト。 */
class DotRedactionTest {
  /** 利用者の状態表示で、秘密情報の保護と利用範囲が契約に従うことを確認する。 */
  @Test
  void credentialAssignmentsBearerAndPrivateKeysAreRemoved() {
    String safe =
        DotPayload.safe(
            "Authorization: Bearer dummy-credential token=hidden password=dummy-password");
    for (String secret : List.of("dummy-credential", "hidden", "dummy-password"))
      assertFalse(safe.contains(secret), safe);
    assertFalse(
        DotPayload.safe("-----BEGIN PRIVATE KEY-----\nnot-a-key\n-----END PRIVATE KEY-----")
            .contains("not-a-key"));
  }

  /** 利用者の状態表示で、指定した条件に対する結果と状態の変化を確認する。 */
  @Test
  void driveUnixAndUncPathsAreRemoved() {
    assertEquals("[LOCAL_PATH]", DotPayload.safe("C:\\Users\\private\\file.txt"));
    assertEquals("[LOCAL_PATH]", DotPayload.safe("/home/private/file.txt"));
    assertEquals("[LOCAL_PATH]", DotPayload.safe("\\\\server\\private\\file.txt"));
  }

  /** 利用者の状態表示で、使用量と受付量が、設定された予算・上限に従うことを確認する。 */
  @Test
  void sanitizationCannotExpandFieldsBeyondTheirByteLimit() {
    assertThrows(
        DotException.class,
        () -> DotPayload.publication(Map.of("instruction", "/a ".repeat(5000))));
  }
}
