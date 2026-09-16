package dev.mikoto2000.rei.application.session;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class SessionTitleTest {
  @ParameterizedTest @ValueSource(ints = {0, 1, 79, 80, 81, 100})
  void truncatesByUnicodeCodePoint(int length) {
    for (String character : new String[]{"a", "あ", "😀"}) {
      assertThat(SessionTitle.from(character.repeat(length)))
          .isEqualTo(character.repeat(Math.min(80, length)));
    }
  }
}
