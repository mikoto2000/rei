package dev.mikoto2000.rei.sound;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.interest.ConsoleInterestNotifier;
import dev.mikoto2000.rei.interest.InterestNotifier;
import dev.mikoto2000.rei.interest.InterestUpdate;
import lombok.RequiredArgsConstructor;

/**
 * 興味関心更新情報を音声通知で伝える InterestNotifier 実装。
 * @Primary として登録され ConsoleInterestNotifier より優先して使用される。
 * 音声通知後に ConsoleInterestNotifier にも委譲してコンソール出力を維持する。
 */
@Primary
@Component
@RequiredArgsConstructor
public class SoundInterestNotifier implements InterestNotifier {

  private final SoundNotificationService soundNotificationService;
  private final ConsoleInterestNotifier consoleInterestNotifier;

  @Override
  public void notifyUpdate(InterestUpdate update) {
    String message = buildMessage(update);
    soundNotificationService.notify(message);
    consoleInterestNotifier.notifyUpdate(update);
  }

  private String buildMessage(InterestUpdate update) {
    return update.topic() + " " + update.summary();
  }
}
