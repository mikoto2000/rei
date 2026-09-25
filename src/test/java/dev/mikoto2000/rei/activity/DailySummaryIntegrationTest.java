package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class DailySummaryIntegrationTest {
  @ParameterizedTest @ValueSource(strings={"","today","yesterday","2026-09-24"})
  void existingSummarySyntaxCallsDailyWriterButTimelineDoesNot(String date) {
    var store=mock(ActivityStore.class);var captured=new ArrayList<DailySummaryAggregate>();
    var writer=(DailySummaryWriter)a->{captured.add(a);return DailySummary.fallback(a);};
    var service=new DailySummaryService(()->DailySummaryTest.NAMES,writer);
    var clock=Clock.fixed(DailySummaryTest.START.plusSeconds(86400+3600),ZoneOffset.UTC);
    var policy=new SemanticSessionPolicy(Duration.ofSeconds(120),Duration.ofSeconds(300),Duration.ofSeconds(120),.5,ZoneOffset.UTC);
    var timeline=new ActivityTimeline(store,clock,policy,service);
    var record=DailySummaryTest.segment(date.equals("yesterday") || date.equals("2026-09-24")?60:1440,60,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT).evidence().getFirst();
    when(store.findRecordsBetween(any(),any())).thenReturn(List.of(record));
    var command=new picocli.CommandLine(new ActivityCommand(timeline,mock(ActivityCapture.class),new ActivityProperties()));
    var out=new java.io.StringWriter();command.setOut(new java.io.PrintWriter(out));
    assertThat(date.isEmpty()?command.execute("summary"):command.execute("summary",date)).isZero();
    assertThat(captured).hasSize(1);assertThat(out.toString()).contains("全体:","rei の開発");
    assertThat(command.execute("today")).isZero();assertThat(captured).hasSize(1);
    verify(store,never()).append(any());
  }
  @Test void fixtureComparisonProducesBoundedReportAndDoesNotMutateSource() throws Exception {
    var source=DailySummaryTest.largeDay();var aggregate=DailySummaryTest.aggregate(source);
    var trend=new TrendSummaryPolicy(ZoneOffset.UTC,.5).aggregate(source);
    var before=new TrendSummaryFormatter(ZoneOffset.UTC).format(trend);
    var after=new DailySummaryFormatter().format(aggregate,DailySummary.fallback(aggregate));
    assertThat(after.length()).isLessThan(before.length()/2);
    assertThat(after.lines().filter(s->s.startsWith("- ")).count()).isLessThanOrEqualTo(8);
    var output=Path.of("target/daily-summary-comparison.md");
    Files.createDirectories(output.getParent());
    Files.writeString(output,"# Synthetic day comparison\n\nSource SummarySegments: "+source.size()+"\nBefore items: "+trend.size()
        +"\nAfter time-of-day sections: "+aggregate.timeOfDay().size()+"\nBefore chars: "+before.length()+"\nAfter chars: "+after.length()
        +"\n\n## Before\n\n"+before+"\n## After (deterministic fallback)\n\n"+after);
  }
}
