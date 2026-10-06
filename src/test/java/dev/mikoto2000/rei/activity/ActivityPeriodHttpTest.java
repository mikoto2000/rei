package dev.mikoto2000.rei.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.mikoto2000.rei.web.*;

@Tag("integration")
class ActivityPeriodHttpTest {
  @TempDir Path dir;
  @Configuration(proxyBeanMethods=false) @EnableWebMvc @Import({SecurityConfig.class,ActivityPeriodController.class,ApiExceptionHandler.class})
  static class Config {@Bean ApiKeyProperties apiKeyProperties(){return new ApiKeyProperties("secret");}}
  SqliteActivityStore store(){return new SqliteActivityStore(new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("activity.db")),new SessionMergePolicy(Duration.ofMinutes(3),ZoneOffset.UTC));}
  @Test void authenticatedPeriodReportReadsSavedEvidenceWithoutCapturingOrCallingAModel() {
    var store=store();store.append(ActivitySemanticTest.dev(0,"Terminal","X"));store.append(ActivitySemanticTest.dev(1,"Terminal","X"));
    var timeline=new ActivityTimeline(store,Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"),ZoneOffset.UTC));
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withBean(ActivityTimeline.class,()->timeline).withUserConfiguration(Config.class).run(context->{
      var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
      mvc.perform(get("/api/v1/activity/period?period=WEEK&date=2026-09-23")).andExpect(status().isUnauthorized());
      var json=mvc.perform(get("/api/v1/activity/period?period=WEEK&date=2026-09-23").header("Authorization","Bearer secret")).andExpect(status().isOk()).andExpect(jsonPath("$.scope").value("LOCAL_DEVICE_OBSERVATIONS")).andExpect(jsonPath("$.anchorDate").value("2026-09-21")).andExpect(jsonPath("$.zone").value("Z")).andExpect(jsonPath("$.partial").value(false)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(json.contains("観測"));assertFalse(json.contains("rei.log"));assertFalse(json.contains("evidence-0"));
      mvc.perform(get("/api/v1/activity/period?period=MONTH").header("Authorization","Bearer secret")).andExpect(status().isOk()).andExpect(jsonPath("$.anchorDate").value("2026-09-01"));
      for(String query:new String[]{"period=DAY","period=WEEK&date=2026-10-07","period=WEEK&date=not-date"})mvc.perform(get("/api/v1/activity/period?"+query).header("Authorization","Bearer secret")).andExpect(status().isBadRequest());
    });assertEquals(2,store.findRecordsBetween(Instant.parse("2026-09-23T00:00:00Z"),Instant.parse("2026-09-24T00:00:00Z")).size());
  }
  @Test void boundedSqliteQueryRejectsOverflowWithoutReturningAPartialAnalysis() {
    var store=store();for(int i=0;i<3;i++)store.append(ActivitySemanticTest.dev(i,"Terminal","X"));var from=Instant.parse("2026-09-23T00:00:00Z");var to=from.plus(Duration.ofDays(1));
    assertEquals(store.findRecordsBetween(from,to),store.findRecordsBetweenBounded(from,to,3));assertThrows(ActivityQueryLimitException.class,()->store.findRecordsBetweenBounded(from,to,2));assertThrows(IllegalArgumentException.class,()->store.findRecordsBetweenBounded(from,to,0));
    var toolkit=new ClassificationToolkit(new ActivityProperties(),null,null,Clock.systemUTC());assertThrows(ActivityQueryLimitException.class,()->toolkit.wrap(store).findRecordsBetweenBounded(from,to,2));
  }
  @Test void unsupportedOldStoreDoesNotFallBackToAnUnboundedRead() {
    var reads=new java.util.concurrent.atomic.AtomicInteger();ActivityStore old=new ActivityStore(){public void append(ActivityRecord r){}public void replace(ActivityRecord r){}public java.util.List<ActivitySession> findBetween(Instant a,Instant b){return java.util.List.of();}public java.util.List<ActivityRecord> findRecordsBetween(Instant a,Instant b){reads.incrementAndGet();return java.util.List.of();}};
    var timeline=new ActivityTimeline(old,Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"),ZoneOffset.UTC));assertThrows(UnsupportedOperationException.class,()->timeline.periodComparisonBounded(ActivityPeriodAnalysis.Period.WEEK,null));assertEquals(0,reads.get());
  }
  @Test void evidenceLimitAndDisabledFeatureReturnExplicitErrorsWithoutAPartialReport() {
    var store=org.mockito.Mockito.mock(ActivityStore.class);org.mockito.Mockito.when(store.findRecordsBetweenBounded(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyInt())).thenThrow(new ActivityQueryLimitException());var timeline=new ActivityTimeline(store,Clock.fixed(Instant.parse("2026-10-06T12:00:00Z"),ZoneOffset.UTC));
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withBean(ActivityTimeline.class,()->timeline).withUserConfiguration(Config.class).run(context->{var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();mvc.perform(get("/api/v1/activity/period").header("Authorization","Bearer secret")).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.report").doesNotExist());});
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withUserConfiguration(Config.class).run(context->{var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();mvc.perform(get("/api/v1/activity/period").header("Authorization","Bearer secret")).andExpect(status().isServiceUnavailable());});
    org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).append(org.mockito.ArgumentMatchers.any());org.mockito.Mockito.verify(store,org.mockito.Mockito.never()).findRecordsBetween(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
  }
  @Test void oversizedStoredPayloadIsRejectedBeforeDecodingAndCancellationCannotReadStatistics() {
    var store=store();store.append(ActivitySemanticTest.dev(0,"Terminal","X"));var ds=new DriverManagerDataSource("jdbc:sqlite:"+dir.resolve("activity.db"));org.springframework.jdbc.core.simple.JdbcClient.create(ds).sql("UPDATE activity_records SET payload=?").param("x".repeat(131073)).update();var from=Instant.parse("2026-09-23T00:00:00Z");var until=from.plus(Duration.ofDays(1));assertThrows(ActivityQueryLimitException.class,()->store.findRecordsBetweenBounded(from,until,10));
    try{Thread.currentThread().interrupt();assertThrows(IllegalStateException.class,()->store.findRecordsBetweenBounded(from,until,10));assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}
  }
}
