package dev.mikoto2000.rei.life;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LifeContextTest {
  static final class Memory implements LifeContext.Store {
    LifeContext.Settings value;

    public LifeContext.Settings load() {
      return value;
    }

    public void save(LifeContext.Settings next, long expected) {
      if ((value == null ? 0 : value.revision()) != expected)
        throw new IllegalArgumentException("revision");
      value = next;
    }
  }

  static final class Time extends Clock {
    Instant now = Instant.parse("2026-10-25T23:59:00Z");

    public Instant instant() {
      return now;
    }

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  @Test
  void changingZoneReevaluatesTheSameInstantRatherThanTheClockZone() {
    var clock = new Time();
    clock.now = Instant.parse("2026-10-26T01:00:00Z");
    var service = new LifeContext(new Memory(), clock);
    var s = service.settings();
    assertTrue(service.status().working());
    service.configure(
        new LifeContext.Settings(
            0,
            "UTC",
            s.workDays(),
            s.workStart(),
            s.workEnd(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null));
    assertFalse(service.status().working());
    clock.now = Instant.parse("2026-10-26T09:00:00Z");
    assertTrue(service.status().working());
  }

  @Test
  void temporaryExceptionBlocksInterruptionsWithoutInventingFreeTime() {
    var clock = new Time();
    clock.now = Instant.parse("2026-10-26T10:00:00Z");
    var service = new LifeContext(new Memory(), clock);
    var s = service.settings();
    service.configure(
        new LifeContext.Settings(
            0,
            s.zone(),
            s.workDays(),
            s.workStart(),
            s.workEnd(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            new LifeContext.ExceptionWindow(
                clock.now.plusSeconds(60),
                clock.now.plusSeconds(120),
                LifeContext.Availability.UNAVAILABLE)));
    var lease = service.begin();
    clock.now = clock.now.plusSeconds(60);
    assertFalse(service.notificationAllowed("NORMAL", ""));
    assertFalse(service.backgroundAllowed("p", "t"));
    assertFalse(service.observationAllowed());
    assertEquals(LifeContext.Availability.UNAVAILABLE, service.status().availability());
    clock.now = clock.now.plusSeconds(120);
    assertFalse(service.valid(lease));
    assertEquals(LifeContext.Availability.UNKNOWN, service.status().availability());
  }

  @Test
  void restAvailabilityExceptionsBackgroundScopeAndEmergencyAreExplicit() {
    var clock = new Time();
    clock.now = Instant.parse("2026-10-26T01:00:00Z");
    var service = new LifeContext(new Memory(), clock);
    var s = service.settings();
    service.configure(
        new LifeContext.Settings(
            0,
            s.zone(),
            s.workDays(),
            s.workStart(),
            s.workEnd(),
            List.of(),
            List.of(new LifeContext.Window(Set.of(DayOfWeek.MONDAY), "23:00", "07:00")),
            List.of(new LifeContext.Window(Set.of(DayOfWeek.MONDAY), "19:00", "21:00")),
            List.of(new LifeContext.Background("p", "t")),
            null,
            Set.of("confirmed-emergency")));
    assertTrue(service.backgroundAllowed("p", "t"));
    assertFalse(service.backgroundAllowed("p", "other"));
    assertTrue(service.notificationAllowed("EMERGENCY", "confirmed-emergency"));
    assertFalse(service.notificationAllowed("EMERGENCY", "model-priority-one"));
    clock.now = Instant.parse("2026-10-26T10:00:00Z");
    assertEquals(LifeContext.Availability.AVAILABLE, service.status().availability());
    clock.now = Instant.parse("2026-10-26T14:00:00Z");
    assertTrue(service.status().resting());
    assertFalse(service.observationAllowed());
    clock.now = Instant.parse("2026-10-26T21:59:00Z");
    assertTrue(service.status().resting());
    clock.now = Instant.parse("2026-10-26T22:00:00Z");
    assertFalse(service.status().resting());
  }

  @Test
  void workBoundariesWeekendsAndUnknownAreConservative() {
    var clock = new Time();
    var service = new LifeContext(new Memory(), clock);
    for (var entry :
        Map.of(
                "2026-10-26T08:59",
                false,
                "2026-10-26T09:00",
                true,
                "2026-10-26T17:59",
                true,
                "2026-10-26T18:00",
                false,
                "2026-10-30T18:00",
                false,
                "2026-10-31T10:00",
                false,
                "2026-11-01T10:00",
                false,
                "2026-11-02T09:00",
                true)
            .entrySet()) {
      clock.now = LocalDateTime.parse(entry.getKey()).atZone(ZoneId.of("Asia/Tokyo")).toInstant();
      assertEquals(entry.getValue(), service.status().working(), entry.getKey());
      assertEquals(
          entry.getValue()
              ? LifeContext.Availability.UNAVAILABLE
              : LifeContext.Availability.UNKNOWN,
          service.status().availability());
    }
  }

  @Test
  void leasesRejectBoundaryCrossingEvenWhenWorkHasEnded() {
    var clock = new Time();
    var service = new LifeContext(new Memory(), clock);
    var lease = service.begin();
    assertTrue(service.valid(lease));
    clock.now = Instant.parse("2026-10-26T09:01:00Z");
    assertFalse(service.valid(lease));
    assertTrue(service.valid(service.begin()));
  }

  @Test
  void settingsSurviveRestartAndInvalidateExistingObservations() {
    var clock = new Time();
    var store = new Memory();
    var service = new LifeContext(store, clock);
    var lease = service.begin();
    var s = service.settings();
    service.configure(
        new LifeContext.Settings(
            0,
            "UTC",
            Set.of(DayOfWeek.MONDAY),
            "09:00",
            "18:00",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null));
    assertFalse(service.valid(lease));
    assertEquals("UTC", new LifeContext(store, clock).settings().zone());
    assertThrows(IllegalArgumentException.class, () -> service.configure(s));
  }

  @Test
  void explicitAvailabilityDoesNotOverrideWorkPrivacyAndEmergencyNeedsAllowlist() {
    var clock = new Time();
    clock.now = Instant.parse("2026-10-26T01:00:00Z");
    var service = new LifeContext(new Memory(), clock);
    assertFalse(service.notificationAllowed("EMERGENCY", "llm_generated"));
    assertFalse(service.notificationAllowed("NORMAL", ""));
    assertFalse(service.backgroundAllowed("p", "t"));
  }
}
