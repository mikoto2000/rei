package dev.mikoto2000.rei.activity;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class DailySummaryTest {
  static final LocalDate DATE=LocalDate.of(2026,9,24);
  static final Instant START=DATE.atStartOfDay(ZoneOffset.UTC).toInstant();
  static final ActivityQueryRange RANGE=new ActivityQueryRange(START,START.plusSeconds(86400));
  static final ProjectNameNormalizer NAMES=new ProjectNameNormalizer(Map.of("sensevoice-input",List.of("sensevoiceinput","sensevoice","sensorvoice-input","sansvoice-input")));
  static SummarySegment segment(int minute,int seconds,String category,String project,String service,EntertainmentDisposition disposition) {
    var at=START.plusSeconds(minute*60L);
    var activity=new ActivityRecord.Activity("m",category,"Terminal",service,"",project);
    var diagnostics=new ClassificationDiagnostics(List.of(),"","fixture",null,true,false,"",List.of(),disposition,1,"","fixture","");
    var record=new ActivityRecord("r"+minute,at,seconds,List.of(),new ForegroundWindow("Terminal",1,service+" "+project,"w"),
        new ActivityRecord.Inference("",List.of(activity)),.95,List.of(),0,false,"fixture",
        new ActivityRecord.Detection(null,List.of(),false,"","",Map.of(),"",null,List.of(),diagnostics));
    return new SummarySegment(at,at.plusSeconds(seconds),seconds,new ActivityRolePolicy().classify(record),List.of(record),List.of());
  }
  static List<SummarySegment> largeDay() throws Exception {
    try(var input=DailySummaryTest.class.getResourceAsStream("/activity/daily-summary-large-day.csv")) {
      return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).lines().skip(1).filter(s->!s.isBlank()).map(line->{
        var p=line.split(",",-1);return segment(Integer.parseInt(p[0]),Integer.parseInt(p[1]),p[2],p[3],p[4],EntertainmentDisposition.valueOf(p[5]));
      }).toList();
    }
  }
  static DailySummaryAggregate aggregate(List<SummarySegment> input) {
    return new DailySummaryAggregator(NAMES,.5).aggregate(DATE,RANGE,ZoneOffset.UTC,input);
  }
  @Test void largeDayIsBoundedAndAliasesMergeWithoutChangingEvidence() throws Exception {
    var source=largeDay();var a=aggregate(source);var result=DailySummary.fallback(a);
    assertThat(a.sourceSegmentCount()).isEqualTo(72);
    assertThat(a.observedSeconds()).isEqualTo(43200);assertThat(a.unobservedSeconds()).isEqualTo(43200);
    assertThat(a.topProjects()).extracting(DailySummaryAggregate.Weighted::name).contains("sensevoice-input").doesNotContain("sensevoiceinput","sensorvoice-input");
    assertThat(a.timeOfDay()).hasSize(4);
    assertThat(result.workThemes()).hasSizeLessThanOrEqualTo(5);assertThat(result.nonWorkActivities()).hasSizeLessThanOrEqualTo(3);
    var text=new DailySummaryFormatter().format(a,result);
    assertThat(text.length()).isLessThan(2400);
    assertThat(text).doesNotContain("GitHub","Terminal","YouTube","X、","00:00–00:10");
    assertThat(text.split("活動内容を十分に判定",-1)).hasSize(2);assertThat(source).isEqualTo(largeDay());
  }
  @Test void timeBucketsRetainTheirOwnMajorProjectThemes() {
    var a=aggregate(List.of(segment(60,1800,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT),
        segment(1200,1800,"development","sensevoiceinput","",EntertainmentDisposition.NON_ENTERTAINMENT)));
    assertThat(a.timeOfDay().get("lateNight").workThemes()).containsExactly("rei の開発");
    assertThat(a.timeOfDay().get("evening").workThemes()).containsExactly("sensevoice-input の開発");
  }
  @Test void boundariesSplitObservedTimeAndGapsStayUnobserved() {
    var a=aggregate(List.of(segment(350,1200,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT)));
    assertThat(a.timeOfDay().get("lateNight").observedSeconds()).isEqualTo(600);
    assertThat(a.timeOfDay().get("morning").observedSeconds()).isEqualTo(600);
    assertThat(a.observedSeconds()).isEqualTo(1200);assertThat(a.unobservedSeconds()).isEqualTo(85200);
    assertThat(a.categorySeconds()).containsEntry("development",1200L);
  }
  @Test void overlappingAndRepeatedEvidenceIsCountedOnce() {
    var first=segment(0,1200,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT);
    var second=segment(10,1200,"social","","X",EntertainmentDisposition.ENTERTAINMENT);
    var a=aggregate(List.of(first,first,second));assertThat(a.observedSeconds()).isEqualTo(1800);
    assertThat(a.categorySeconds().values().stream().mapToLong(Long::longValue).sum()).isEqualTo(1800);
  }
  @Test void workOnlyAndTechnicalMediaDoNotBecomeLeisure() {
    var a=aggregate(List.of(segment(600,1200,"development","rei","GitHub",EntertainmentDisposition.NON_ENTERTAINMENT),
        segment(630,1200,"media","","YouTube",EntertainmentDisposition.NON_ENTERTAINMENT)));
    assertThat(DailySummary.fallback(a).nonWorkActivities()).isEmpty();
    assertThat(a.majorWorkBlocks()).hasSize(1);assertThat(a.majorLeisureBlocks()).isEmpty();
  }
  @Test void uncertainMediaDoesNotBecomeEntertainmentAndLeisureDoesNotInventWork() {
    var uncertain=aggregate(List.of(segment(600,600,"media","","YouTube",EntertainmentDisposition.UNCERTAIN)));
    assertThat(DailySummary.fallback(uncertain).nonWorkActivities()).isEmpty();
    var leisure=aggregate(List.of(segment(600,1800,"social","","X",EntertainmentDisposition.ENTERTAINMENT)));
    assertThat(DailySummary.fallback(leisure).workThemes()).isEmpty();assertThat(leisure.majorLeisureBlocks()).hasSize(1);
  }
  @Test void safeNormalizationAndExplicitAliasesOnly() {
    assertThat(NAMES.normalize(" SenseVoice_Input ")).isEqualTo("sensevoice-input");
    assertThat(NAMES.normalize("sensorvoice-input")).isEqualTo("sensevoice-input");
    assertThat(NAMES.normalize("livevingo")).isNotEqualTo(NAMES.normalize("livetrans"));
    assertThat(new ProjectNameNormalizer(Map.of("voice",List.of("SenseVoice_Input"))).normalize("sensevoice-input")).isEqualTo("voice");
    for(var invalid:List.of(Map.of("",List.of("x")),Map.of("a",List.of("")),Map.of("a",List.of("x","x")),Map.of("a",List.of("x"),"b",List.of("X"))))
      assertThatThrownBy(()->new ProjectNameNormalizer(invalid)).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void implementationNamesAreNotWorkThemes() {
    var a=aggregate(List.of(segment(0,1800,"development","AgentEventFactory","",EntertainmentDisposition.NON_ENTERTAINMENT),
        segment(60,1800,"development","project.cd","",EntertainmentDisposition.NON_ENTERTAINMENT),
        segment(120,1800,"development","repl","",EntertainmentDisposition.NON_ENTERTAINMENT)));
    assertThat(DailySummary.fallback(a).workThemes()).contains("開発").noneMatch(s->s.contains("AgentEvent") || s.contains("project.cd") || s.contains("repl"));
  }
  @Test void twoHundredSegmentsStillProduceBoundedInputAndOutput() throws Exception {
    var source=java.util.stream.IntStream.range(0,200).mapToObj(i->segment(i*7,120,"development","project-"+i,"GitHub",EntertainmentDisposition.NON_ENTERTAINMENT)).toList();
    var a=aggregate(source);
    assertThat(new DailySummaryFormatter().format(a,DailySummary.fallback(a)).length()).isLessThan(2400);
    var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(a);
    assertThat(json.length()).isLessThan(16000);assertThat(a.topProjects()).hasSize(5);assertThat(a.services()).hasSizeLessThanOrEqualTo(3);
    assertThat(a.frequentProjectSwitches()).isTrue();
  }
  @Test void unknownOnlyHasOneNoteAndNoInventedWorkOrLeisure() {
    var a=aggregate(List.of(segment(0,600,"unknown","","",EntertainmentDisposition.UNCERTAIN),segment(600,600,"unknown","","",EntertainmentDisposition.UNCERTAIN)));
    var result=DailySummary.fallback(a);assertThat(result.workThemes()).isEmpty();assertThat(result.nonWorkActivities()).isEmpty();
    assertThat(result.timeOfDay()).isEmpty();
    assertThat(new DailySummaryFormatter().format(a,result).split("活動内容を十分に判定",-1)).hasSize(2);
  }
  @Test void daylightSavingDayAndSixHourBoundaryUseLocalClock() {
    var zone=ZoneId.of("America/New_York");var date=LocalDate.of(2026,3,8);
    var range=new ActivityQueryRange(date.atStartOfDay(zone).toInstant(),date.plusDays(1).atStartOfDay(zone).toInstant());
    var start=date.atTime(5,50).atZone(zone).toInstant();
    var original=segment(0,1200,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT).evidence().getFirst();
    var record=new ActivityRecord(original.id(),start,1200,original.observations(),original.foreground(),original.inference(),original.confidence(),List.of(),0,false,"dst",original.detection());
    var s=new SummarySegment(start,start.plusSeconds(1200),1200,new ActivityRolePolicy().classify(record),List.of(record),List.of());
    var a=new DailySummaryAggregator(NAMES,.5).aggregate(date,range,zone,List.of(s));
    assertThat(a.observedSeconds()+a.unobservedSeconds()).isEqualTo(23*3600);
    assertThat(a.timeOfDay().get("lateNight").observedSeconds()).isEqualTo(600);
    assertThat(a.timeOfDay().get("morning").observedSeconds()).isEqualTo(600);
  }
  @Test void invalidWriterFallsBackAndEmptyDayNeverCallsWriter() {
    var calls=new java.util.concurrent.atomic.AtomicInteger();
    var service=new DailySummaryService(()->NAMES,a->{calls.incrementAndGet();throw new IllegalStateException("failure");});
    assertThat(service.summarize(DATE,RANGE,ZoneOffset.UTC,.5,List.of())).contains("Activity は記録されていません");
    assertThat(calls).hasValue(0);
    assertThat(service.summarize(DATE,RANGE,ZoneOffset.UTC,.5,List.of(segment(1,60,"development","rei","",EntertainmentDisposition.NON_ENTERTAINMENT)))).contains("全体:","rei").doesNotContain("failure");
    assertThat(calls).hasValue(1);
  }
}
