package dev.mikoto2000.rei.application.session;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CursorCodecTest {
  @Test void roundTripsUnicodeAndNanosecondsAndRejectsWrongScopeAndTrailingData() {
    var codec = new CursorCodec();
    var key = new CursorKey(Instant.parse("2026-09-16T08:00:00.123456789Z"), "project:日本語:😀");
    var token = codec.encode("sessions:project", key);
    assertThat(codec.decode("sessions:project", token)).isEqualTo(key);
    assertThatThrownBy(() -> codec.decode("turns:project", token)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> codec.decode("sessions:project", token + "AAAA")).isInstanceOf(IllegalArgumentException.class);
    assertThat(codec.decode("sessions:project", null)).isNull();
  }
}
