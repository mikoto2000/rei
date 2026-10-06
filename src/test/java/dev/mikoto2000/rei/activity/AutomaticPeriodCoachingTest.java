package dev.mikoto2000.rei.activity;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.topic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutomaticPeriodCoachingTest {
  @TempDir Path root;
  final Clock clock=Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"),ZoneOffset.UTC);
  PeriodCoachingStore store() {return new SqlitePeriodCoachingStore(new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:sqlite:"+root.resolve("coaching.db")));}
  PeriodCoaching.Settings settings(){return new PeriodCoaching.Settings(true,Set.of("development"),.6,60,.001,.25,7);}
  ActivityTimeline timeline() {
    var timeline=mock(ActivityTimeline.class);
    when(timeline.periodComparison(any(),isNull())).thenAnswer(invocation->{
      ActivityPeriodAnalysis.Period period=invocation.getArgument(0);
      var first=period==ActivityPeriodAnalysis.Period.WEEK?LocalDate.of(2026,9,28):LocalDate.of(2026,9,1);
      var previous=period==ActivityPeriodAnalysis.Period.WEEK?first.minusWeeks(1):first.minusMonths(1);
      return new ActivityTimeline.PeriodComparison(aggregate(period,first,1200,6000),aggregate(period,previous,6000,1200),ZoneOffset.UTC);
    });return timeline;
  }
  ActivityPeriodAnalysis.Aggregate aggregate(ActivityPeriodAnalysis.Period period,LocalDate first,long target,long other) {
    var from=first.atStartOfDay(ZoneOffset.UTC).toInstant();var end=(period==ActivityPeriodAnalysis.Period.WEEK?first.plusWeeks(1):first.plusMonths(1)).atStartOfDay(ZoneOffset.UTC).toInstant();
    return new ActivityPeriodAnalysis.Aggregate(new ActivityPeriodAnalysis.Range(period,first,from,end,false),target+other,Duration.between(from,end).toSeconds()-target-other,
        Map.of("development",target,"research",other),Map.of(),Map.of(),Map.of(),0);
  }
  ActivityProperties properties(){var p=new ActivityProperties();p.setEnabled(true);p.getCoaching().setAutomaticEnabled(true);p.getCoaching().setMonthlyEnabled(false);return p;}
  @Test void disabledByDefaultAndStoredOptInRequiredThenReceiptSurvivesRestartAndSharesManualBudget() {
    var p=new ActivityProperties();var timeline=timeline();var store=store();var service=new PeriodCoachingService(timeline,store,clock);var tracker=mock(AgentActivityTracker.class);var messages=new ArrayList<AgentMessage>();
    var job=new AutomaticPeriodCoaching(p,service,messages::add,tracker,()->false,clock);
    job.tick();verifyNoInteractions(timeline);assertTrue(messages.isEmpty());
    p.setEnabled(true);p.getCoaching().setAutomaticEnabled(true);p.getCoaching().setMonthlyEnabled(false);
    job.tick();verifyNoInteractions(timeline);assertTrue(messages.isEmpty());
    store.configure(settings());job.tick();assertEquals(1,messages.size());assertTrue(messages.getFirst().content().startsWith("自動Coaching"));
    assertEquals(MessageOrigin.BEHAVIOR,messages.getFirst().origin());assertEquals("PERIOD_COACHING_WEEK",messages.getFirst().metadata().get("triggerType"));
    job.tick();assertEquals(1,messages.size());
    var restarted=new PeriodCoachingService(timeline,store(),clock);
    new AutomaticPeriodCoaching(p,restarted,messages::add,tracker,()->false,clock).tick();assertEquals(1,messages.size());
    assertTrue(restarted.evaluate(ActivityPeriodAnalysis.Period.WEEK,null).contains("ALREADY_SHOWN"));
  }
  @Test void monthlyAndWeeklyShareCooldownAndOnlyOneAdviceCanBePublishedPerTick() {
    var p=properties();p.getCoaching().setMonthlyEnabled(true);var store=store();store.configure(settings());var timeline=timeline();var messages=new ArrayList<AgentMessage>();
    var job=new AutomaticPeriodCoaching(p,new PeriodCoachingService(timeline,store,clock),messages::add,mock(AgentActivityTracker.class),()->false,clock);
    job.tick();assertEquals(1,messages.size());assertEquals("PERIOD_COACHING_MONTH",messages.getFirst().metadata().get("triggerType"));
    job.tick();assertEquals(1,messages.size());assertEquals("COOLDOWN",job.status());
  }
  @Test void busyPausedAndChangedSettingsSuppressDeliveryWithoutConsumingAnUnrelatedAdvice() {
    var p=properties();var store=store();store.configure(settings());var timeline=timeline();var tracker=mock(AgentActivityTracker.class);var messages=new ArrayList<AgentMessage>();
    var paused=new java.util.concurrent.atomic.AtomicBoolean(true);var service=new PeriodCoachingService(timeline,store,clock);
    var job=new AutomaticPeriodCoaching(p,service,messages::add,tracker,paused::get,clock);
    job.tick();verifyNoInteractions(timeline);paused.set(false);when(tracker.isAgentBusy()).thenReturn(true);job.tick();verifyNoInteractions(timeline);
    when(tracker.isAgentBusy()).thenReturn(false);
    when(timeline.periodComparison(any(),isNull())).thenAnswer(i->{store.setEnabled(false);return new ActivityTimeline.PeriodComparison(aggregate(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,9,28),1200,6000),aggregate(ActivityPeriodAnalysis.Period.WEEK,LocalDate.of(2026,9,21),6000,1200),ZoneOffset.UTC);});
    job.tick();assertTrue(messages.isEmpty());assertEquals("SETTINGS_CHANGED",job.status());
  }
  @Test void deliveryFailureConsumesReservationAndRestartDoesNotReplay() {
    var p=properties();var store=store();store.configure(settings());var timeline=timeline();var calls=new java.util.concurrent.atomic.AtomicInteger();
    AgentMessagePublisher publisher=message->{calls.incrementAndGet();throw new IllegalStateException("delivery unknown");};
    var job=new AutomaticPeriodCoaching(p,new PeriodCoachingService(timeline,store,clock),publisher,mock(AgentActivityTracker.class),()->false,clock);
    job.tick();assertEquals("FAILED",job.status());assertEquals(1,calls.get());
    new AutomaticPeriodCoaching(p,new PeriodCoachingService(timeline,store(),clock),publisher,mock(AgentActivityTracker.class),()->false,clock).tick();assertEquals(1,calls.get());
  }
  @Test void invalidIntervalsAreRejectedAndNoPeriodNotificationConfigurationIsEnabledByDefault() {
    var p=new ActivityProperties();assertFalse(p.getCoaching().isAutomaticEnabled());p.getCoaching().setCheckIntervalSeconds(0);
    assertThrows(IllegalArgumentException.class,p::validate);
  }
}
