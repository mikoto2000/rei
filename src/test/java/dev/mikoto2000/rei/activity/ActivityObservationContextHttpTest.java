package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.mikoto2000.rei.web.*;
import dev.mikoto2000.rei.core.project.*;

@Tag("integration")
class ActivityObservationContextHttpTest {
 @TempDir Path dir;final Instant at=Instant.parse("2026-10-05T09:00:00Z");
 @Configuration(proxyBeanMethods=false) @EnableWebMvc @Import({SecurityConfig.class,ActivityObservationContextController.class,ApiExceptionHandler.class})
 static class Config {@Bean ApiKeyProperties apiKeyProperties(){return new ApiKeyProperties("secret");}}
 ActivityRecord record(String project,String id,ActivityEvidence.WorkReference reference){var base=ActivitySemanticTest.dev(0,"Terminal","X");var evidence=new ActivityEvidence(at,base.foreground(),List.of(),"rei",project,List.of(),null,reference);return new ActivityRecord(id,at,60,List.of(),base.foreground(),base.inference(),.8,List.of(),0,false,"c",new ActivityRecord.Detection(evidence,List.of(),false,"EVIDENCE_ONLY","READY",Map.of(),""));}
 ActivityEvidence.WorkReference reference(String project){return new ActivityEvidence.WorkReference(project,at,3,at.minusSeconds(20),new ActivityEvidence.GitReference("main","abc",at),List.of(new ActivityEvidence.ItemReference("item","CURRENT_WORK","OPEN","EXPLICIT",List.of(new ActivityEvidence.SourceReference("event","session","turn","run","command-ref","src/Main.java",at.minusSeconds(30))))),false);}
 @Test void authenticatedProjectObservationReportSurvivesRestartWithoutCurrentHistory() {
  var projects=new ProjectRegistry(dir.resolve("projects.json"));var project=projects.resolve(dir);var source=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("activity.db"));var merge=new SessionMergePolicy(Duration.ofSeconds(90),ZoneOffset.UTC);var store=new SqliteActivityStore(source,merge);store.append(record(project.id(),"observation",reference(project.id())));store.append(record("other","private-other",reference("other")));
  var restarted=new SqliteActivityStore(source,merge);var service=new ActivityWorkContextService(restarted,p->{throw new AssertionError("current history must not be consulted");},Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));
  new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withBean(ProjectRegistry.class,()->projects).withBean(ActivityWorkContextService.class,()->service).withUserConfiguration(Config.class).run(context->{var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();String url="/api/v1/projects/"+project.id()+"/activity/observation-context?date=2026-10-05";
   mvc.perform(get(url)).andExpect(status().isUnauthorized());String json=mvc.perform(get(url).header("Authorization","Bearer secret")).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("PROJECT_OBSERVATION_CONTEXT")).andExpect(jsonPath("$.projectId").value(project.id())).andExpect(jsonPath("$.partial").value(false)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);assertTrue(json.contains("src/Main.java"));assertTrue(json.contains("command-ref"));assertFalse(json.contains("private-other"));
   mvc.perform(get("/api/v1/projects/unknown/activity/observation-context").header("Authorization","Bearer secret")).andExpect(status().isNotFound());mvc.perform(get(url.replace("2026-10-05","2026-10-06")).header("Authorization","Bearer secret")).andExpect(status().isBadRequest());
  });
 }
 @Test void unsupportedOldStoreCannotFallbackToUnboundedRecords() {
  var store=org.mockito.Mockito.mock(ActivityStore.class,org.mockito.Mockito.CALLS_REAL_METHODS);var service=new ActivityWorkContextService(store,p->List.of(),Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));assertThrows(UnsupportedOperationException.class,()->service.observationLinksBounded("p","today"));org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).findRecordsBetween(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
 }
 @Test void missingMismatchedAndFutureSnapshotsCannotBecomeCurrentEvidence() {
  var store=org.mockito.Mockito.mock(ActivityStore.class);var future=new ActivityEvidence.WorkReference("p",at,3,at.plusSeconds(1),null,List.of(),false);var partial=new ActivityEvidence.WorkReference("p",at,3,at.minusSeconds(20),null,reference("p").items(),true);
  org.mockito.Mockito.when(store.findRecordsBetweenBounded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.eq(5000))).thenReturn(List.of(record("p","old",null),record("p","future",future),record("p","other",reference("other")),record("other","private",reference("other")),record("p","saved",partial)));
  var service=new ActivityWorkContextService(store,p->{throw new AssertionError();},Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));var links=service.observationLinksBounded("p","today");assertEquals(3,links.missingContextRecords());assertEquals(1,links.links().size());assertTrue(links.partial());assertEquals("saved",links.links().getFirst().recordId());
 }
 @Test void linkLimitIsExplicitAndDuplicateObservationsAreNotCountedTwice() {
  var store=org.mockito.Mockito.mock(ActivityStore.class);var records=new ArrayList<ActivityRecord>();for(int i=0;i<129;i++)records.add(record("p","id-"+i,reference("p")));records.addFirst(records.getFirst());org.mockito.Mockito.when(store.findRecordsBetweenBounded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(records);
  var service=new ActivityWorkContextService(store,p->List.of(),Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));var result=service.observationLinksBounded("p","today");assertEquals(128,result.links().size());assertTrue(result.partial());assertEquals(0,result.missingContextRecords());
 }
 @Test void outputOverflowCannotPublishAPartialReportAndStoredItemBoundsAreEnforced() {
  var store=org.mockito.Mockito.mock(ActivityStore.class);var source=new ActivityEvidence.SourceReference("e".repeat(60),"s".repeat(60),"t".repeat(60),"r".repeat(60),"c".repeat(60),"f".repeat(60),at.minusSeconds(1));var item=new ActivityEvidence.ItemReference("item","CURRENT_WORK","OPEN","EXPLICIT",Collections.nCopies(8,source));
  var saved=new ActivityEvidence.WorkReference("p",at,3,at.minusSeconds(1),null,Collections.nCopies(20,item),false);org.mockito.Mockito.when(store.findRecordsBetweenBounded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(record("p","id",saved)));
  var service=new ActivityWorkContextService(store,p->List.of(),Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));assertThrows(ActivityQueryLimitException.class,()->service.formatObservationBounded(service.observationLinksBounded("p","today").links()));
  var oversized=new ActivityEvidence.WorkReference("p",at,3,at.minusSeconds(1),null,Collections.nCopies(21,item),false);org.mockito.Mockito.when(store.findRecordsBetweenBounded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(record("p","id",oversized)));assertThrows(ActivityQueryLimitException.class,()->service.observationLinksBounded("p","today"));
 }
 @Test void cancelledAndEmptyMidnightQueriesDoNotReadSavedEvidence() {
  var store=org.mockito.Mockito.mock(ActivityStore.class);var service=new ActivityWorkContextService(store,p->List.of(),Clock.fixed(Instant.parse("2026-10-05T00:00:00Z"),ZoneOffset.UTC));assertTrue(service.observationLinksBounded("p","today").links().isEmpty());
  Thread.currentThread().interrupt();try{assertThrows(java.util.concurrent.CancellationException.class,()->service.observationLinksBounded("p","today"));}finally{Thread.interrupted();}org.mockito.Mockito.verifyNoInteractions(store);
 }
 @Test void oversizedEvidenceAndDisabledActivityReturnNoPartialHttpResult() {
  var projects=new ProjectRegistry(dir.resolve("projects.json"));var project=projects.resolve(dir);var store=org.mockito.Mockito.mock(ActivityStore.class);org.mockito.Mockito.when(store.findRecordsBetweenBounded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt())).thenThrow(new ActivityQueryLimitException());var service=new ActivityWorkContextService(store,p->List.of(),Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC));
  new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withBean(ProjectRegistry.class,()->projects).withBean(ActivityWorkContextService.class,()->service).withUserConfiguration(Config.class).run(context->{var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();mvc.perform(get("/api/v1/projects/"+project.id()+"/activity/observation-context").header("Authorization","Bearer secret")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.report").doesNotExist());});
  new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withBean(ProjectRegistry.class,()->projects).withUserConfiguration(Config.class).run(context->{var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();mvc.perform(get("/api/v1/projects/"+project.id()+"/activity/observation-context").header("Authorization","Bearer secret")).andExpect(status().isServiceUnavailable());});
 }
 @Test void longDisplayReferencesAreMarkedIncompleteAndFutureToolSourcesAreMissing() {
  var store=org.mockito.Mockito.mock(ActivityStore.class);var future=new ActivityEvidence.SourceReference("event","s","t","r","c",null,at.plusSeconds(1));var item=new ActivityEvidence.ItemReference("i".repeat(241),"CURRENT_WORK","OPEN","EXPLICIT",List.of());var saved=new ActivityEvidence.WorkReference("p",at,3,at.minusSeconds(1),null,List.of(item),false);var invalid=new ActivityEvidence.WorkReference("p",at,3,at.minusSeconds(1),null,List.of(new ActivityEvidence.ItemReference("item","CURRENT_WORK","OPEN","EXPLICIT",List.of(future))),false);
  org.mockito.Mockito.when(store.findRecordsBetweenBounded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(record("p","saved",saved),record("p","future",invalid)));var result=new ActivityWorkContextService(store,p->List.of(),Clock.fixed(at.plusSeconds(60),ZoneOffset.UTC)).observationLinksBounded("p","today");assertTrue(result.partial());assertEquals(1,result.links().size());assertEquals(1,result.missingContextRecords());
 }
}
