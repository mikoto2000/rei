package dev.mikoto2000.rei.activity;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static dev.mikoto2000.rei.activity.DailySummaryTest.*;
import static dev.mikoto2000.rei.activity.ThemeEnrichmentTest.withText;
import static dev.mikoto2000.rei.activity.EntertainmentDisposition.*;
class ThemeAttributionTest {
  static SummarySegment sample(int minute,int seconds,String project,String topic) {
    return withText(segment(minute,seconds,"development",project,"",NON_ENTERTAINMENT),topic,"");
  }
  static List<SummarySegment> qualityDay() {
    var list=new ArrayList<SummarySegment>();
    list.add(sample(0,600,"project",""));
    list.add(sample(20,600,"sensevoice",""));
    list.add(sample(40,600,"sensevoice-input",""));
    list.add(sample(60,60,"rei","transcription"));
    list.add(sample(62,600,"rei",""));
    for(int i=0;i<4;i++)list.add(sample(90+i*2,120,"livevingo","translation transcription"));
    return list;
  }
  static SummarySegment evidence(SummarySegment s,boolean background,double pc,double tc,String title) {
    var r=s.evidence().getFirst();var d=r.detection();
    var v=VisionDiagnostics.initial().with(background,VisionDiagnostics.State.USED,null);
    var detection=new ActivityRecord.Detection(null,List.of(background?"VISION_BACKGROUND":"VISION_FOREGROUND"),true,
        "fixture","",Map.of(),"",new ActivityFieldConfidence(.95,.95,.95,pc,tc),List.of(),d.diagnostics(),v);
    var fg=new ForegroundWindow("Terminal",1,title,"w");
    var record=new ActivityRecord(r.id(),r.capturedAt(),r.durationEstimate(),r.observations(),fg,r.inference(),
        r.confidence(),r.screenshotReferences(),0,false,r.continuityId(),detection);
    return new SummarySegment(s.startedAt(),s.endedAt(),s.observedSeconds(),new ActivityRolePolicy().classify(record),List.of(record),List.of());
  }
  static List<ProjectThemeAssociation> relations(List<SummarySegment> input) {
    var stats=new WorkThemeAggregation();
    for(var s:input)for(var r:s.evidence()) {
      var p=new ActivityRolePolicy().classify(r).primary();
      stats.add(NAMES.project(p),"development",WorkThemeAggregation.topics(p,r),r,p,s.startedAt(),s.endedAt());
    }
    return stats.associations();
  }
  @Test void repeatedForegroundObservationsHaveProvenanceAndStrongSupport() {
    var source=new ArrayList<SummarySegment>();
    for(int i=0;i<20;i++)source.add(evidence(sample(i*2,120,"livevingo","translation"),false,.95,.95,"livevingo translation"));
    var a=relations(source).getFirst();
    assertThat(a.strong()).isTrue();assertThat(a.supportingObservationCount()).isEqualTo(20);
    assertThat(a.supportingDuration()).isEqualTo(2400);assertThat(a.foregroundObservationCount()).isEqualTo(20);
    assertThat(a.supportingSessionCount()).isEqualTo(1);assertThat(a.longestContinuousSeconds()).isEqualTo(2400);
    assertThat(a.evidenceSources()).contains(ActivityEvidenceDisplayFormatter.Source.FOREGROUND_VISION,ActivityEvidenceDisplayFormatter.Source.WINDOW_METADATA);
    assertThat(a.provenance()).hasSize(20).allMatch(p->p.origin()==ProjectThemeAssociation.Origin.SAME_ACTIVITY_TITLE && p.monitor().equals("m"));
    assertThat(aggregate(source).dominantThemes()).containsExactly("livevingo の翻訳");
  }
  @Test void backgroundAloneCannotEstablishAssociation() {
    var source=List.of(evidence(sample(0,1800,"rei","transcription"),true,.95,.95,"rei"));
    var relation=relations(source).getFirst();
    assertThat(relation.foregroundObservationCount()).isZero();
    assertThat(relation.evidenceSources()).contains(ActivityEvidenceDisplayFormatter.Source.BACKGROUND_VISION);
    assertThat(relation.associationConfidence()).isLessThan(.45);
    assertThat(aggregate(source).dominantThemes()).containsExactly("rei の開発");
  }
  @Test void partialProjectNameInForegroundIsNotCorroboration() {
    var a=relations(List.of(evidence(sample(0,600,"rei","transcription"),true,.95,.95,"freight transcription"))).getFirst();
    assertThat(a.strong()).isFalse();assertThat(a.foregroundObservationCount()).isZero();
  }
  @Test void foregroundMetadataCanCorroboratePairEvenWithBackgroundPresent() {
    var a=relations(List.of(evidence(sample(0,600,"rei","Activity classification"),true,.95,.95,"rei Activity classification"))).getFirst();
    assertThat(a.strong()).isTrue();assertThat(a.foregroundObservationCount()).isEqualTo(1);
  }
  @Test void durationAndConfidenceAreIndependentInputs() {
    var shortPair=relations(List.of(sample(0,60,"rei","transcription"))).getFirst();
    var longPair=relations(List.of(sample(0,600,"rei","transcription"))).getFirst();
    var lowProject=relations(List.of(evidence(sample(0,600,"rei","transcription"),false,.6,.95,"rei"))).getFirst();
    var lowTheme=relations(List.of(evidence(sample(0,600,"rei","transcription"),false,.95,.6,"rei"))).getFirst();
    assertThat(shortPair.projectConfidence()).isEqualTo(shortPair.themeConfidence());
    assertThat(shortPair.associationConfidence()).isLessThan(.75).isLessThan(longPair.associationConfidence());
    assertThat(longPair.strong()).isTrue();assertThat(lowProject.strong()).isFalse();assertThat(lowTheme.strong()).isFalse();
  }
  @Test void sameSessionAndSegmentDoNotCrossAssociate() {
    var source=List.of(sample(0,600,"rei","Activity classification"),sample(20,600,"sensevoice","transcription"));
    var merged=new SummarySegment(source.getFirst().startedAt(),source.getLast().endedAt(),1200,
        source.getFirst().roles(),source.stream().flatMap(s->s.evidence().stream()).toList(),List.of("same-session"));
    var a=aggregate(List.of(merged,merged));
    assertThat(a.dominantThemes()).containsExactlyInAnyOrder("rei のActivity分類","sensevoice-input の文字起こし");
    assertThat(a.projectThemeStats()).flatExtracting(DailySummaryAggregate.ProjectThemeStat::strongAssociations)
        .allMatch(p->p.supportingObservationCount()==1 && p.supportingDuration()==600);
  }
  @Test void noisyTextAndWeakThemeOnlyFallBackToGeneric() {
    assertThat(aggregate(List.of(sample(0,600,"rei","build_and_chat software_development coding"))).dominantThemes()).containsExactly("rei の開発");
    assertThat(aggregate(List.of(sample(0,60,"","transcription"))).dominantThemes()).containsExactly("開発");
    assertThat(aggregate(List.of(sample(0,600,"","speech translation"))).dominantThemes()).containsExactly("音声翻訳");
  }
  @Test void serviceOrApplicationNamesAreNotProjectsButExplicitAliasesWin() {
    assertThat(NAMES.project(new ActivityRecord.Activity("m","development","custom-editor","","","custom-editor"))).isEmpty();
    assertThat(NAMES.project(new ActivityRecord.Activity("m","development","Terminal","custom-service","","custom-service"))).isEmpty();
    var configured=new ProjectNameNormalizer(Map.of("my-project",List.of("project")));
    assertThat(configured.project(new ActivityRecord.Activity("m","development","Terminal","","","project"))).isEqualTo("my-project");
    assertThat(NAMES.project("rei")).isEqualTo("rei");
  }
  @Test void aliasStoreFeedsCanonicalProjectsToWriterAndFallback(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    var file=dir.resolve("aliases.yaml");
    Files.writeString(file,"projectAliases:\\n  sensevoice-input: [sensevoice, sensevoiceinput, sensorvoice-input]\\n".replace("\\n","\n"));
    var source=List.of(sample(0,600,"sensevoice",""),sample(20,600,"sensevoice-input",""),sample(40,600,"sensorvoice-input",""));
    var service=new DailySummaryService(new ProjectAliasStore(file),a->{
      assertThat(a.dominantThemes()).containsExactly("sensevoice-input の開発");
      assertThat(a.topProjects()).extracting(DailySummaryAggregate.Weighted::name).containsExactly("sensevoice-input");
      assertThat(a.timeOfDay().get("lateNight").dominantProjects()).containsExactly("sensevoice-input");
      throw new IllegalStateException("fixture failure");
    });
    assertThat(service.summarize(DailySummaryTest.DATE,RANGE,java.time.ZoneOffset.UTC,.5,source)).contains("sensevoice-input").doesNotContain("sensevoice の","sensorvoice-input");
    assertThat(source.getFirst().evidence().getFirst().inference().activities().getFirst().projectCandidate()).isEqualTo("sensevoice");
  }
  @Test void strongInputIsBoundedAndDoesNotSerializeObservationIds() throws Exception {
    var source=new ArrayList<SummarySegment>();
    for(int b=0;b<4;b++)for(int i=0;i<6;i++)source.add(sample(b*360+i*20,600,"project-"+i+"x".repeat(51),"translation transcription"));
    var a=aggregate(source);
    var json=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).writeValueAsString(a);
    assertThat(json).contains("strongAssociations","supportingObservationCount").doesNotContain("provenance","observationId");
    assertThat(json.length()).isLessThan(16000);
    assertThat(new DailySummaryFormatter().format(a,DailySummary.fallback(a)).length()).isLessThan(2400);
  }
  @Test void weakAssociationNeverReturnsOnWriterFailure() {
    var source=List.of(sample(0,60,"rei","transcription"));
    var service=new DailySummaryService(()->NAMES,a->{throw new IllegalStateException();});
    assertThat(service.summarize(DailySummaryTest.DATE,RANGE,java.time.ZoneOffset.UTC,.5,source)).contains("rei の開発").doesNotContain("rei の文字起こし");
  }
  @Test void genericProjectIsRejected() {
    for(var name:List.of("project","repository","repo","workspace","development","software","coding","source","src","main","app","application","code","a"))
      assertThat(NAMES.project(name)).as(name).isEmpty();
    assertThat(aggregate(List.of(sample(0,600,"project",""))).dominantThemes()).containsExactly("開発");
  }
  @Test void weakPairFallsBackEvenWithHighIndividualConfidence() {
    var a=aggregate(List.of(sample(0,60,"rei","transcription")));
    assertThat(a.dominantThemes()).containsExactly("rei の開発");
    assertThat(a.timeOfDay().get("lateNight").workThemes()).containsExactly("rei の開発");
  }
  @Test void summaryOnlyDoesNotAttributeEvenAfterLongSupport() {
    var a=aggregate(List.of(withText(sample(0,1800,"rei",""),"","transcription")));
    assertThat(a.dominantThemes()).containsExactly("rei の開発");
  }
  @Test void strongSameObservationPairsAreKeptWithoutCrossProduct() {
    var a=aggregate(List.of(sample(0,600,"rei","Activity classification"),
        sample(20,600,"sensevoice","speech-to-text")));
    assertThat(a.dominantThemes()).containsExactlyInAnyOrder("rei のActivity分類","sensevoice-input の文字起こし");
    assertThat(a.dominantThemes()).doesNotContain("rei の文字起こし","sensevoice-input のActivity分類");
  }
  @Test void naturalOutputDoesNotRepeatMechanicalPhrases() throws Exception {
    for(var source:List.of(ThemeEnrichmentTest.differentProjects(),largeDay())) {
      var a=aggregate(source);
      var text=new DailySummaryFormatter().format(a,DailySummary.fallback(a));
      assertThat(text).doesNotContain("作業テーマとして見られました","補助表示");
    }
  }
  @Test void qualityFixtureComparison() throws Exception {
    var out=new StringBuilder();
    for(var input:List.of(qualityDay(),largeDay(),ThemeEnrichmentTest.differentProjects())) {
      var a=aggregate(input);var s=DailySummary.fallback(a);
      out.append(ThemeEnrichmentTest.metrics(a,s)).append("\n")
          .append("genericProjects=").append(s.workThemes().stream().filter(t->t.startsWith("project の")).count())
          .append(", weakPairs=").append(s.workThemes().stream().filter(t->t.equals("rei の文字起こし")).count())
          .append(", duplicateCanonical=").append(s.workThemes().stream().filter(t->t.startsWith("sensevoice の")).count())
          .append("\n\n").append(new DailySummaryFormatter().format(a,s)).append("\n\n");
    }
    Files.writeString(Path.of("target/theme-attribution-comparison.txt"),out);
  }
}
