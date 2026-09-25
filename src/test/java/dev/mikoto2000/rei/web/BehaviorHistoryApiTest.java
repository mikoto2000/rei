package dev.mikoto2000.rei.web;
import java.nio.file.Path;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class BehaviorHistoryApiTest {
  @TempDir Path dir;
  @Test void restoredNotificationUsesCompatibleAssistantOnlyTurnWithSourceMetadata() {
    var at=Instant.parse("2026-09-25T12:00:00Z");
    var sessions=new FileSessionRepository(dir.resolve("sessions.json"));
    sessions.accept(new SessionMetadata("project:550e8400-e29b-41d4-a716-446655440000:chat:main","550e8400-e29b-41d4-a716-446655440000","chat",at,at),()->{});
    new ConversationTurnStore(dir).appendAssistantNotification(new ConversationLogEntry(
        "project:550e8400-e29b-41d4-a716-446655440000:chat:main","chat","assistant",at.atOffset(ZoneOffset.UTC),"戻ろう。",1,
        "BEHAVIOR_NOTIFICATION","b1",Map.of("severity","NOTICE")));
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true")
      .withBean(SessionQueryService.class,()->new SessionQueryService(new FileSessionRepository(dir.resolve("sessions.json")),new ConversationTurnStore(dir)))
      .withUserConfiguration(SessionControllerTest.Config.class).run(context->{
        var mvc=MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
        mvc.perform(get("/api/v1/sessions/project:550e8400-e29b-41d4-a716-446655440000:chat:main/turns").header("Authorization","Bearer secret"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
          .andExpect(jsonPath("$.items[0].userMessage").value(""))
          .andExpect(jsonPath("$.items[0].assistantMessage").value("戻ろう。"))
          .andExpect(jsonPath("$.items[0].source").value("BEHAVIOR_NOTIFICATION"))
          .andExpect(jsonPath("$.items[0].sourceId").value("b1"))
          .andExpect(jsonPath("$.items[0].metadata.severity").value("NOTICE"))
          .andExpect(jsonPath("$.items[0].createdAt").value(at.toString()));
      });
  }
}
