package dev.mikoto2000.rei.smalltalk;

import org.springframework.stereotype.Component;

@Component
public class ConsoleSmallTalkNotifier implements SmallTalkNotifier {

  @Override
  public void notifyTopic(String topic) {
    System.out.println("雑談 | " + topic);
  }
}
