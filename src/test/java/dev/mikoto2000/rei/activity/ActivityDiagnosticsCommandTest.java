package dev.mikoto2000.rei.activity;

import java.io.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActivityDiagnosticsCommandTest {
  @ParameterizedTest @ValueSource(strings={"", "today", "yesterday", "2026-09-23"})
  void verboseWorksWithEveryDateAndDoesNotRunCapture(String day) {
    var store=mock(ActivityStore.class);var capture=mock(ActivityCapture.class);
    when(store.findRecordsBetween(any(),any())).thenAnswer(inv->{var r=ActivitySemanticTest.dev(0,"Terminal","X");return List.of(new ActivityRecord(r.id(),((Instant)inv.getArgument(0)).plusSeconds(3600),60,r.observations(),r.foreground(),r.inference(),r.confidence(),List.of(),0,false));});
    var timeline=new ActivityTimeline(store,Clock.fixed(ActivitySemanticTest.START,ZoneOffset.UTC));
    var command=new picocli.CommandLine(new ActivityCommand(timeline,capture,new ActivityProperties()));
    var out=new StringWriter();command.setOut(new PrintWriter(out));command.setErr(new PrintWriter(new StringWriter()));
    assertEquals(0,day.isEmpty()?command.execute("--verbose"):command.execute(day,"--verbose"));
    assertTrue(out.toString().contains("evidence:"));assertTrue(out.toString().contains("confidence:"));
    verifyNoInteractions(capture);
  }
  @Test void verboseBeforeDateIsAcceptedAndSummaryStillUsesOnlyTrendPath() {
    var timeline=mock(ActivityTimeline.class);var presentation=mock(ActivityTimelinePresentationService.class);
    var activity=new ActivityCommand(timeline,mock(ActivityCapture.class),new ActivityProperties());activity.timelinePresentation(presentation);
    var command=new picocli.CommandLine(activity);command.setOut(new PrintWriter(new StringWriter()));
    assertEquals(0,command.execute("--verbose","yesterday"));verify(presentation).format("yesterday",true);
    assertEquals(0,command.execute("today"));verify(presentation).format("today",false);
    clearInvocations(presentation);
    assertEquals(0,command.execute("summary","yesterday"));verify(timeline).trendSummary("yesterday");verifyNoInteractions(presentation);
    assertTrue(command.getUsageMessage().contains("--verbose"));
  }
}
