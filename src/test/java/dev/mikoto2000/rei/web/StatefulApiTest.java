package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.state.StatefulOperationService;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.reminder.ReminderService;
import dev.mikoto2000.rei.interest.InterestUpdateService;
import dev.mikoto2000.rei.memory.service.MemoryService;
import dev.mikoto2000.rei.memory.configuration.MemoryProperties;
import dev.mikoto2000.rei.skills.AgentSkillRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.sqlite.SQLiteDataSource;
import java.nio.file.Path;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class StatefulApiTest {
  @TempDir Path directory;
  @Test void existingPersistentOperationsHaveExplicitValidationAndHttpSemantics() throws Exception {
    var data=new SQLiteDataSource(); data.setUrl("jdbc:sqlite:"+directory.resolve("data.db"));
    var feeds=new FeedService(data); var reminders=new ReminderService(data); var interests=new InterestUpdateService(data);
    var memories=new MemoryService(data,new MemoryProperties(true,20,80,10,3,2000,60,new MemoryProperties.ExpiryDefaults(30,365))); var skills=mock(AgentSkillRepository.class);
    var service=new StatefulOperationService(feeds,reminders,interests,memories,skills);
    var mvc=MockMvcBuilders.standaloneSetup(new StatefulController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(post("/api/v1/feed").contentType("application/json").content("{\"url\":\"https://example.com/feed\",\"displayName\":\"Example\"}"))
        .andExpect(status().isCreated()).andExpect(header().string("Location","/api/v1/feed/1")).andExpect(jsonPath("$.id").value(1));
    mvc.perform(post("/api/v1/feed").contentType("application/json").content("{\"url\":\"https://example.com/feed\"}")).andExpect(status().isConflict());
    mvc.perform(patch("/api/v1/feed/1").contentType("application/json").content("{\"displayName\":\"Renamed\",\"enabled\":false}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.displayName").value("Renamed"));
    mvc.perform(patch("/api/v1/feed/99").contentType("application/json").content("{\"enabled\":true}")).andExpect(status().isNotFound());
    for(String body:new String[]{"{}","{\"displayName\":\" \"}"})
      mvc.perform(patch("/api/v1/feed/1").contentType("application/json").content(body)).andExpect(status().isBadRequest());
    mvc.perform(delete("/api/v1/feed/1")).andExpect(status().isNoContent()); mvc.perform(delete("/api/v1/feed/1")).andExpect(status().isNoContent());
    assertThat(feeds.list()).isEmpty();
    mvc.perform(post("/api/v1/reminders").contentType("application/json").content("{\"message\":\"meeting\",\"at\":\"2027-01-01T09:00:00Z\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.type").value("AT_TIME")).andExpect(header().string("Location","/api/v1/reminders/1"));
    mvc.perform(get("/api/v1/reminders/1")).andExpect(status().isOk()).andExpect(jsonPath("$.message").value("meeting"));
    mvc.perform(get("/api/v1/reminders")).andExpect(jsonPath("$.length()").value(1));
    mvc.perform(post("/api/v1/reminders").contentType("application/json").content("{\"message\":\"before\",\"target\":\"2027-01-01T09:00:00Z\",\"minutesBefore\":30}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.remindAt").value("2027-01-01T08:30Z"));
    mvc.perform(get("/api/v1/reminders/99")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/reminders/1")).andExpect(status().isNoContent()); mvc.perform(delete("/api/v1/reminders/1")).andExpect(status().isNoContent());
    var memoryResponse=mvc.perform(post("/api/v1/memories").contentType("application/json").content("{\"content\":\"a fact\",\"type\":\"KNOWLEDGE\",\"scope\":\"LONG_TERM\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ACTIVE")).andReturn().getResponse();
    String memory=memoryResponse.getHeader("Location");
    mvc.perform(get(memory)).andExpect(status().isOk()).andExpect(jsonPath("$.content").value("a fact"));
    mvc.perform(get("/api/v1/memories")).andExpect(jsonPath("$.length()").value(1));
    mvc.perform(delete(memory)).andExpect(status().isNoContent()); mvc.perform(delete(memory)).andExpect(status().isNoContent());
    mvc.perform(get(memory)).andExpect(jsonPath("$.status").value("DELETED"));
    mvc.perform(get("/api/v1/memories/unknown")).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/memories/unknown")).andExpect(status().isNotFound());
    var scoped=memories.save(new dev.mikoto2000.rei.memory.model.Memory(null,"project private",dev.mikoto2000.rei.memory.model.MemoryType.KNOWLEDGE,
        dev.mikoto2000.rei.memory.model.MemoryScope.PROJECT,dev.mikoto2000.rei.memory.model.MemoryStatus.ACTIVE,0.8,null,null,null));
    mvc.perform(get("/api/v1/memories/"+scoped.id())).andExpect(status().isNotFound());
    mvc.perform(delete("/api/v1/memories/"+scoped.id())).andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/memories")).andExpect(jsonPath("$.length()").value(0));
    String interest="{\"topic\":\"Java\",\"reason\":\"development\",\"searchQuery\":\"Java news\",\"summary\":\"updates\",\"sourceUrls\":[\"https://example.com\"]}";
    mvc.perform(post("/api/v1/interests").contentType("application/json").content(interest)).andExpect(status().isCreated()).andExpect(jsonPath("$.topic").value("Java"));
    mvc.perform(post("/api/v1/interests").contentType("application/json").content(interest)).andExpect(status().isConflict());
    mvc.perform(get("/api/v1/interests")).andExpect(jsonPath("$.length()").value(1));
    mvc.perform(post("/api/v1/skills/reload")).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0)); verify(skills).reload();
    for(String path:new String[]{"/api/v1/reminders","/api/v1/interests","/api/v1/memories","/api/v1/feed"}) {
      mvc.perform(post(path).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
      mvc.perform(post(path).contentType("application/json").content("{\"path\":\"C:/secret\",\"projectId\":\"wrong\",\"sessionId\":\"wrong\"}"))
          .andExpect(status().isBadRequest());
    }
    mvc.perform(post("/api/v1/reminders").contentType("application/json").content("{\"message\":\"bad\",\"target\":\"2027-01-01T09:00:00Z\",\"minutesBefore\":-1}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/memories").contentType("application/json").content("{\"content\":\"fact\",\"type\":\"INVALID\",\"scope\":\"LONG_TERM\"}"))
        .andExpect(status().isBadRequest());
    for(String path:new String[]{"/api/v1/reminders/1","/api/v1/memories/1","/api/v1/interests/1","/api/v1/profile","/api/v1/skills/one"})
      assertThat(mvc.perform(patch(path).contentType("application/json").content("{}")).andReturn().getResponse().getStatus()).isIn(404,405);
    mvc.perform(post("/api/v1/memories").contentType("application/json").content("{\"content\":\"fact\",\"type\":\"KNOWLEDGE\",\"scope\":\"PROJECT\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/feed").contentType("application/json").content("{\"url\":\"https://example.com/other\",\"projectId\":\"fake\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/memories").contentType("application/json").content("{\"content\":\"fact\",\"type\":\"KNOWLEDGE\",\"scope\":\"LONG_TERM\",\"sessionId\":\"fake\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/skills/reload").contentType("application/json").content("{\"path\":\"C:/secret\"}"))
        .andExpect(status().isBadRequest());
    verify(skills,times(1)).reload();
    mvc.perform(post("/api/v1/reminders").contentType("application/json").content("{\"message\":\"bad\",\"at\":\"not-a-date\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/memories").contentType("application/json").content("{\"content\":\"fact\",\"type\":\"KNOWLEDGE\",\"scope\":\"LONG_TERM\",\"confidence\":2}"))
        .andExpect(status().isBadRequest());
  }
}
