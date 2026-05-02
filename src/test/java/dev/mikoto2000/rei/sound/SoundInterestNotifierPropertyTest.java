package dev.mikoto2000.rei.sound;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import dev.mikoto2000.rei.interest.ConsoleInterestNotifier;
import dev.mikoto2000.rei.interest.InterestUpdate;

/**
 * Feature: sound-notification
 * Property 7: SoundInterestNotifier が音声通知とコンソール通知の両方を実行する
 */
class SoundInterestNotifierPropertyTest {

  static Stream<InterestUpdate> updateVariants() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    return Stream.of(
        new InterestUpdate(1L, "Neovim", "r", "q", "最新情報", List.of(), now),
        new InterestUpdate(2L, "Java 25", "r", "q", "新機能まとめ", List.of("https://example.com"), now),
        new InterestUpdate(3L, "Spring Boot", "r", "q", "マイグレーションガイド", List.of(), now),
        new InterestUpdate(4L, "", "r", "q", "空トピック", List.of(), now),
        new InterestUpdate(5L, "Rust", "r", "q", "", List.of(), now),
        new InterestUpdate(6L, "Docker", "r", "q", "compose v2 tips", List.of("https://a.com", "https://b.com"), now),
        new InterestUpdate(7L, "日本語トピック", "r", "q", "日本語要約", List.of(), now),
        new InterestUpdate(8L, "topic with spaces", "r", "q", "summary with spaces", List.of(), now),
        new InterestUpdate(9L, "special!@#", "r", "q", "special$%^", List.of(), now),
        new InterestUpdate(10L, "long".repeat(50), "r", "q", "long summary".repeat(10), List.of(), now)
    );
  }

  @ParameterizedTest(name = "topic={0}")
  @MethodSource("updateVariants")
  @Tag("sound-notification-property-7-soundInterestNotifier")
  void notifyUpdateCallsBothSoundAndConsole(InterestUpdate update) {
    SoundNotificationService soundService = mock(SoundNotificationService.class);
    ConsoleInterestNotifier consoleNotifier = mock(ConsoleInterestNotifier.class);
    SoundInterestNotifier notifier = new SoundInterestNotifier(soundService, consoleNotifier);

    notifier.notifyUpdate(update);

    // 音声通知がトピック名と要約を結合したメッセージで呼ばれること
    String expectedMessage = update.topic() + " " + update.summary();
    verify(soundService).notify(eq(expectedMessage));

    // コンソール通知も同じ update で呼ばれること
    verify(consoleNotifier).notifyUpdate(eq(update));
  }
}
