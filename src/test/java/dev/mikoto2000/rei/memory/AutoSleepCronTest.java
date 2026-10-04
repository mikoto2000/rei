package dev.mikoto2000.rei.memory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import dev.mikoto2000.rei.memory.service.*;
import dev.mikoto2000.rei.memory.configuration.*;
import dev.mikoto2000.rei.topic.DefaultAgentActivityTracker;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
class AutoSleepCronTest {
  final Instant start=Instant.parse("2026-10-04T00:00:00Z");
  final SleepService sleep=mock(SleepService.class);
  final DefaultAgentActivityTracker activity=new DefaultAgentActivityTracker(Clock.fixed(start.minusSeconds(600),ZoneOffset.UTC));
  final MutableClock clock=new MutableClock(start);
  AutoSleepService service;
  @AfterEach void close(){if(service!=null)service.close();}
  void setup(String cron,String zone){
    service=new AutoSleepService(sleep,new MemoryProperties(true,20,80,10,3,2000,60,null),new AutoSleepProperties(true,Duration.ofMinutes(5),2,Duration.ofMinutes(10),cron,zone),activity,clock);
    service.afterTerminal(new AgentRunContext("r","s",java.nio.file.Path.of("."),"p"));when(sleep.unsleptTurns("s")).thenReturn(2L);
  }
  @Test void cronWaitsUntilDueAndRunsOnlyOneBatchPerOccurrence() throws Exception {
    setup("0 1 * * * *","UTC");service.tick();verifyNoInteractions(sleep);
    var done=new CountDownLatch(1);doAnswer(a->{done.countDown();return null;}).when(sleep).sleep(eq("s"),eq("p"),eq(false),any());
    clock.time=start.plusSeconds(60);service.tick();assertTrue(done.await(2,TimeUnit.SECONDS));clock.time=start.plusSeconds(1200);
    service.tick();verify(sleep,times(1)).sleep(anyString(),anyString(),anyBoolean(),any());
  }
  @Test void dailyCronUsesExplicitTimeZone(){
    setup("0 0 10 * * *","Asia/Tokyo");clock.time=start.plusSeconds(3599);service.tick();verifyNoInteractions(sleep);
    clock.time=start.plusSeconds(3600);service.tick();verify(sleep).unsleptTurns("s");
  }
  @Test void invalidCronAndZoneFailConfiguration(){
    assertThrows(IllegalArgumentException.class,()->new AutoSleepProperties(true,null,2,null,"broken","UTC"));
    assertThrows(IllegalArgumentException.class,()->new AutoSleepProperties(true,null,2,null,"0 0 10 * * *","invalid"));
  }
  @Test void busyAndRecentActivityDeferDueOccurrenceWithoutCatchupStorm() throws Exception {
    setup("0 1 * * * *","UTC");clock.time=start.plusSeconds(60);activity.recordAgentStarted(clock.time);service.tick();verifyNoInteractions(sleep);
    clock.time=start.plusSeconds(7200);activity.recordAgentCompleted(clock.time);service.tick();verifyNoInteractions(sleep);
    var done=new CountDownLatch(1);doAnswer(a->{done.countDown();return null;}).when(sleep).sleep(eq("s"),eq("p"),eq(false),any());
    clock.time=start.plusSeconds(7500);service.tick();assertTrue(done.await(2,TimeUnit.SECONDS));
    clock.time=start.plusSeconds(8400);service.tick();verify(sleep,times(1)).unsleptTurns("s");
  }
  @Test void bindsCronAndLegacyDefaultsFromConfiguration() {
    var source=new org.springframework.boot.context.properties.source.MapConfigurationPropertySource(java.util.Map.of(
        "rei.memory.auto-sleep.enabled","true","rei.memory.auto-sleep.cron","0 0 10 * * *","rei.memory.auto-sleep.zone","Asia/Tokyo"));
    var bound=new org.springframework.boot.context.properties.bind.Binder(source).bind("rei.memory.auto-sleep",org.springframework.boot.context.properties.bind.Bindable.of(AutoSleepProperties.class)).get();
    assertTrue(bound.enabled());assertEquals("0 0 10 * * *",bound.cron());assertEquals("Asia/Tokyo",bound.zone());assertEquals(Duration.ofMinutes(5),bound.minimumIdle());
  }
  static class MutableClock extends Clock {
    Instant time;MutableClock(Instant time){this.time=time;}
    public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return time;}
  }
}
