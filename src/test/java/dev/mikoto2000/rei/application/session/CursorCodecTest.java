package dev.mikoto2000.rei.application.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** 続きの取得位置Codecの結果と状態の契約を検証するテスト。 */
class CursorCodecTest {
  /** 会話の継続管理で、異常条件を所定の失敗として扱い、結果を呼び出し元へ伝えることを確認する。 */
  @Test
  void roundTripsUnicodeAndNanosecondsAndRejectsWrongScopeAndTrailingData() {
    var codec = new CursorCodec();
    var key = new CursorKey(Instant.parse("2026-09-16T08:00:00.123456789Z"), "project:日本語:😀");
    var token = codec.encode("sessions:project", key);
    assertThat(codec.decode("sessions:project", token)).isEqualTo(key);
    assertThatThrownBy(() -> codec.decode("turns:project", token))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decode("sessions:project", token + "AAAA"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(codec.decode("sessions:project", null)).isNull();
  }
}
