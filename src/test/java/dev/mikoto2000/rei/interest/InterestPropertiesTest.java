package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InterestPropertiesTest {

  @Test
  void topicUpdateIntervalHoursDefaultIs24() {
    InterestProperties props = new InterestProperties();
    assertEquals(24, props.getTopicUpdateIntervalHours());
  }

  @Test
  void pastQueryLookbackDaysDefaultIs7() {
    InterestProperties props = new InterestProperties();
    assertEquals(7, props.getPastQueryLookbackDays());
  }
}
