package dev.mikoto2000.rei.sound;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Feature: sound-notification
 * Property 6: SoundNotificationTools が SoundNotificationService に委譲し結果を返す
 */
class SoundNotificationToolsPropertyTest {

  static Stream<String> messageVariants() {
    return Stream.of(
        "hello",
        "",
        "日本語メッセージ",
        "with spaces",
        "special!@#$%",
        "改行なし",
        "a".repeat(200),
        "🎵emoji🎵",
        "  前後スペース  ",
        "null文字列ではない"
    );
  }

  @ParameterizedTest(name = "message={0}")
  @MethodSource("messageVariants")
  @Tag("sound-notification-property-6-toolsDelegation")
  void toolsDelegatesToServiceAndReturnsNonNull(String message) {
    SoundNotificationService service = mock(SoundNotificationService.class);
    SoundNotificationTools tools = new SoundNotificationTools(service);

    String result = tools.notify(message);

    // SoundNotificationService.notify() が同じメッセージで呼ばれること
    verify(service).notify(message);
    // null でない文字列が返されること
    assertNotNull(result, "戻り値が null でないべき。message=" + message);
  }
}
