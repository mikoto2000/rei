package dev.mikoto2000.rei.life;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class LifePreparationBudgetTest {
  @Test
  void repeatedClockHourUsesTheNextOccurrenceOfWorkStart() {
    var time = new LifeContextTest.Time();
    time.now = Instant.parse("2026-10-25T01:15:00Z");
    var life = new LifeContext(new LifeContextTest.Memory(), time);
    life.configure(
        new LifeContext.Settings(
            0,
            "Europe/Berlin",
            Set.of(DayOfWeek.SUNDAY),
            "02:45",
            "03:30",
            List.of(),
            List.of(),
            List.of(new LifeContext.Window(Set.of(DayOfWeek.SUNDAY), "02:00", "04:00")),
            List.of(),
            null));
    assertEquals(30, life.availableMinutes());
  }

  @Test
  void skippedClockHourStopsAvailabilityAtTheGapBoundary() {
    var time = new LifeContextTest.Time();
    time.now = Instant.parse("2026-03-29T00:45:00Z");
    var life = new LifeContext(new LifeContextTest.Memory(), time);
    life.configure(
        new LifeContext.Settings(
            0,
            "Europe/Berlin",
            Set.of(DayOfWeek.SUNDAY),
            "02:30",
            "04:00",
            List.of(),
            List.of(),
            List.of(new LifeContext.Window(Set.of(DayOfWeek.SUNDAY), "01:00", "05:00")),
            List.of(),
            null));
    assertEquals(15, life.availableMinutes());
  }

  @Test
  void explicitPersonalWindowIsBoundedByRestAndKnownBusyNeverBecomesAvailable() {
    var time = new LifeContextTest.Time();
    time.now = Instant.parse("2026-10-26T10:00:00Z");
    var life = new LifeContext(new LifeContextTest.Memory(), time);
    var s = life.settings();
    life.configure(
        new LifeContext.Settings(
            0,
            s.zone(),
            s.workDays(),
            s.workStart(),
            s.workEnd(),
            List.of(),
            List.of(new LifeContext.Window(Set.of(DayOfWeek.MONDAY), "19:40", "22:00")),
            List.of(new LifeContext.Window(Set.of(DayOfWeek.MONDAY), "18:00", "21:00")),
            List.of(),
            null));
    assertEquals(40, life.availableMinutes());
    var beans = new StaticListableBeanFactory();
    beans.addBean("busy", (LifeAvailability) at -> true);
    life.setAvailabilitySources(beans.getBeanProvider(LifeAvailability.class));
    assertEquals(LifeContext.Availability.BUSY, life.status().availability());
    assertEquals(0, life.availableMinutes());
    org.junit.jupiter.api.Assertions.assertFalse(life.notificationAllowed("NORMAL", ""));
  }

  @Test
  void unavailableSavedScheduleCannotEstablishAvailability() {
    var time = new LifeContextTest.Time();
    time.now = Instant.parse("2026-10-26T10:00:00Z");
    var life = new LifeContext(new LifeContextTest.Memory(), time);
    var beans = new StaticListableBeanFactory();
    beans.addBean(
        "broken",
        (LifeAvailability)
            at -> {
              throw new IllegalStateException();
            });
    life.setAvailabilitySources(beans.getBeanProvider(LifeAvailability.class));
    assertEquals(LifeContext.Availability.UNKNOWN, life.status().availability());
    assertEquals(0, life.availableMinutes());
  }
}
