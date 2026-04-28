package dev.mikoto2000.rei.interest;

import org.springframework.stereotype.Component;

@Component
public class ConsoleInterestNotifier implements InterestNotifier {

  @Override
  public void notifyUpdate(InterestUpdate update) {
    System.out.println("興味更新 | " + update.topic());
    System.out.println("- " + update.summary());
    if (!update.sourceUrls().isEmpty()) {
      System.out.println("- URL: " + update.sourceUrls().getFirst());
    }
  }
}
