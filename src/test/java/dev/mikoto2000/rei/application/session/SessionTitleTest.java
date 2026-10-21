package dev.mikoto2000.rei.application.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 会話タイトルの結果と状態の契約を検証するテスト。 */
class SessionTitleTest {
  /** 会話の継続管理で、入力や保存形式を契約に従って解釈し、情報を受け渡すことを確認する。 */
  @ParameterizedTest
  @ValueSource(ints = {0, 1, 79, 80, 81, 100})
  void truncatesByUnicodeCodePoint(int length) {
    for (String character : new String[] {"a", "あ", "😀"}) {
      assertThat(SessionTitle.from(character.repeat(length)))
          .isEqualTo(character.repeat(Math.min(80, length)));
    }
  }
}
