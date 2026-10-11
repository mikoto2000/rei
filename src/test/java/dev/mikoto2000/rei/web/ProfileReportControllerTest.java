package dev.mikoto2000.rei.web;
import static org.junit.jupiter.api.Assertions.*;
import dev.mikoto2000.rei.event.*;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileReportControllerTest {
  @TempDir Path directory;
  @Test void namedViewsReuseTheLegacyShellReportAndChartDefaults() {
    var mapper=new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    var store=new ProfileEventLogStore(directory.resolve("profile.log"),mapper);
    var events=new AgentEventFactory(Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"),ZoneOffset.UTC));
    store.append(events.runStarted("fixture","chat",null));store.append(events.runCompleted("fixture",250));
    var controller=new ProfileReportController(store);
    assertTrue(controller.summary().text().contains("events: 2"));assertTrue(controller.summary().text().contains("avg=250"));
    assertEquals(store.file().toString()+System.lineSeparator(),controller.path().text());
    assertTrue(controller.chart(60,10).text().contains("##########"));
    assertTrue(controller.mermaid(12).text().contains("xychart-beta"));
    assertEquals(controller.chart(1,1).text(),controller.chart(0,0).text());
  }
}
