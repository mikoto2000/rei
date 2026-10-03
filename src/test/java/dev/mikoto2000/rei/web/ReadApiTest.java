package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.read.ReadQueryService;
import dev.mikoto2000.rei.feed.FeedService;
import dev.mikoto2000.rei.skills.*;
import dev.mikoto2000.rei.event.ProfileEventLogStore;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.briefing.BriefingService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;

class ReadApiTest {
  @Test void failuresAndMalformedJsonUseSafeCommonErrors() throws Exception {
    var feeds = mock(FeedService.class);
    when(feeds.list()).thenThrow(new IllegalStateException("secret-key"));
    var service = new ReadQueryService(feeds, mock(AgentSkillRepository.class), mock(ProfileEventLogStore.class), mock(SearchKnowledgeService.class), mock(BriefingService.class));
    var mvc = MockMvcBuilders.standaloneSetup(new ReadController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/api/v1/feed")).andExpect(status().isInternalServerError()).andExpect(content().json("{\"message\":\"Internal server error\"}"));
    mvc.perform(post("/api/v1/search").contentType("application/json").content("{"))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid request"));
  }
  @Test void readsUseExplicitDtosAndDoNotExposeWritesOrFilesystem() throws Exception {
    var feeds = mock(FeedService.class);
    var skills = mock(AgentSkillRepository.class);
    when(feeds.list()).thenReturn(List.of());
    var skill = new AgentSkill("test", "description", true, java.nio.file.Path.of("private"), java.nio.file.Path.of("private/SKILL.md"), "instructions");
    when(skills.findAll()).thenReturn(List.of(skill));
    when(skills.findByName("test")).thenReturn(Optional.of(skill));
    var service = new ReadQueryService(feeds, skills, mock(ProfileEventLogStore.class), mock(SearchKnowledgeService.class), mock(BriefingService.class));
    var mvc = MockMvcBuilders.standaloneSetup(new ReadController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
    mvc.perform(get("/api/v1/feed")).andExpect(status().isOk()).andExpect(content().json("[]"));
    var body = mvc.perform(get("/api/v1/skills/test")).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("test")).andReturn().getResponse().getContentAsString();
    assertThat(body).doesNotContain("directory", "skillFile", "private");
    mvc.perform(get("/api/v1/skills/unknown")).andExpect(status().isNotFound());
    mvc.perform(post("/api/v1/skills")).andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/api/v1/feed")).andExpect(status().isMethodNotAllowed());
    mvc.perform(post("/api/v1/search").contentType("application/json").content("{\"query\":\" \"}")).andExpect(status().isBadRequest());
    verifyNoInteractions(service.searches());
  }
}
