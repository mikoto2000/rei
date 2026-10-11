package dev.mikoto2000.rei.web;

import java.nio.file.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import dev.mikoto2000.rei.application.session.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

@org.junit.jupiter.api.Tag("integration")
class SessionControllerTest {
  @TempDir Path directory;
  @Configuration(proxyBeanMethods = false) @EnableWebMvc
  @Import({SecurityConfig.class, SessionController.class, ApiExceptionHandler.class})
  static class Config {
    @Bean ApiKeyProperties apiKeyProperties() { return new ApiKeyProperties("secret"); }
  }
  @Test void emptySessionAndResponseStyleUseRegisteredProjectWithoutStartingARun()throws Exception {
    var repo=new FileSessionRepository(directory.resolve("sessions.json"));var clock=Clock.systemUTC();
    var lifecycle=new SessionLifecycle(repo,clock);var projects=new dev.mikoto2000.rei.core.project.ProjectRegistry(directory.resolve("projects.json"));var project=projects.resolve(directory);
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withBean(SessionLifecycle.class,()->lifecycle)
        .withBean(dev.mikoto2000.rei.core.project.ProjectRegistry.class,()->projects)
        .withBean(SessionQueryService.class,()->new SessionQueryService(repo,new dev.mikoto2000.rei.conversation.ConversationTurnStore(directory)))
        .withUserConfiguration(Config.class).run(context->{
          var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
          mvc.perform(post("/api/v1/sessions").contentType("application/json").content("{\"projectId\":\""+project.id()+"\"}")).andExpect(status().isUnauthorized());
          var accepted=mvc.perform(post("/api/v1/sessions").header("Authorization","Bearer secret").contentType("application/json").content("{\"projectId\":\""+project.id()+"\",\"title\":\"empty\"}")).andExpect(status().isCreated()).andReturn();
          var id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(accepted.getResponse().getContentAsString()).path("sessionId").asText();
          mvc.perform(patch("/api/v1/sessions/"+id+"/response-style").header("Authorization","Bearer secret").contentType("application/json").content("{\"projectId\":\""+project.id()+"\",\"style\":\"CONVERSATION\",\"voiceOnly\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.style").value("CONVERSATION")).andExpect(jsonPath("$.voiceOnly").value(true));
          mvc.perform(get("/api/v1/sessions/"+id+"/response-style?projectId="+project.id()).header("Authorization","Bearer secret")).andExpect(status().isOk());
          mvc.perform(patch("/api/v1/sessions/"+id+"/response-style").header("Authorization","Bearer secret").contentType("application/json").content("{\"projectId\":\"wrong\",\"style\":\"NORMAL\"}")).andExpect(status().isNotFound());
          mvc.perform(post("/api/v1/sessions").header("Authorization","Bearer secret").contentType("application/json").content("{\"projectId\":\""+project.id()+"\",\"path\":\"arbitrary\"}")).andExpect(status().isBadRequest());
          assertEquals("empty",repo.findById(id).orElseThrow().title());
        });
  }
  @Test void explicitEndRequiresAuthenticationAndMatchingSavedProjectAndKeepsMetadata() {
    var repo=new FileSessionRepository(directory.resolve("sessions.json"));var now=Instant.parse("2026-10-06T12:00:00Z");
    var session=new SessionMetadata("ended","project","title",now,now);repo.accept(session,()->{});
    var ended=new java.util.ArrayList<SessionMetadata>();var lifecycle=new SessionLifecycle(repo,Clock.fixed(now,ZoneOffset.UTC));lifecycle.onEnded(ended::add);
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true").withBean(SessionLifecycle.class,()->lifecycle)
        .withBean(SessionQueryService.class,()->new SessionQueryService(repo,new dev.mikoto2000.rei.conversation.ConversationTurnStore(directory)))
        .withUserConfiguration(Config.class).run(context->{
          var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",jakarta.servlet.Filter.class)).build();
          mvc.perform(post("/api/v1/sessions/ended/end").contentType("application/json").content("{\"projectId\":\"project\"}")).andExpect(status().isUnauthorized());
          mvc.perform(post("/api/v1/sessions/ended/end").header("Authorization","Bearer secret").contentType("application/json").content("{\"projectId\":\"other\"}")).andExpect(status().isConflict());
          mvc.perform(post("/api/v1/sessions/ended/end").header("Authorization","Bearer secret").contentType("application/json").content("{\"projectId\":\"project\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("ended"));
          mvc.perform(post("/api/v1/sessions/missing/end").header("Authorization","Bearer secret").contentType("application/json").content("{\"projectId\":\"project\"}")).andExpect(status().isNotFound());
          assertEquals(java.util.List.of(session),ended);assertEquals(java.util.Optional.of(session),repo.findById("ended"));
        });
  }
  @Test void authenticatedReadOnlyDtosAndValidation() {
    var repo = new FileSessionRepository(directory.resolve("sessions.json"));
    var now = Instant.parse("2026-09-16T08:00:00Z");
    repo.accept(new SessionMetadata("project:p:chat:one", "p", "日本語 title", now, now), () -> {});
    repo.accept(new SessionMetadata("two", "q", "second", now, now.minusSeconds(1)), () -> {});
    var turns = new dev.mikoto2000.rei.conversation.ConversationTurnStore(directory);
    for (String id : new String[]{"b", "a"}) {
      var run = new dev.mikoto2000.rei.core.chat.AgentRunContext(id, "two", directory);
      turns.start(run, "question " + id, now);
      turns.finish(run, dev.mikoto2000.rei.conversation.ConversationTurnStore.Status.COMPLETED, "answer " + id);
    }
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true")
        .withBean(SessionQueryService.class, () -> new SessionQueryService(repo, turns))
        .withUserConfiguration(Config.class).run(context -> {
      var mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
      for (String path : new String[]{"/api/v1/sessions", "/api/v1/sessions/project:p:chat:one", "/api/v1/sessions/project:p:chat:one/turns"}) {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized());
      }
      mvc.perform(get("/api/v1/sessions").header("Authorization", "Bearer secret"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
          .andExpect(jsonPath("$.items[0].sessionId").value("project:p:chat:one"))
          .andExpect(jsonPath("$.items[0].title").value("日本語 title"))
          .andExpect(jsonPath("$.items[0].createdAt").value(now.toString()))
          .andExpect(jsonPath("$.nextCursor").value(org.hamcrest.Matchers.nullValue()));
      mvc.perform(get("/api/v1/sessions/project:p:chat:one").header("Authorization", "Bearer secret"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5))
          .andExpect(jsonPath("$.projectId").value("p")).andExpect(jsonPath("$.updatedAt").value(now.toString()));
      mvc.perform(get("/api/v1/sessions/missing").header("Authorization", "Bearer secret")).andExpect(status().isNotFound());
      mvc.perform(get("/api/v1/sessions?projectId=q&limit=1").header("Authorization", "Bearer secret"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sessionId").value("two"));
      mvc.perform(get("/api/v1/sessions?limit=1").header("Authorization", "Bearer secret"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.nextCursor").isString());
      for (String query : new String[]{"limit=0", "limit=-1", "limit=101", "limit=abc", "cursor=invalid", "cursor="})
        mvc.perform(get("/api/v1/sessions?" + query).header("Authorization", "Bearer secret")).andExpect(status().isBadRequest());
      mvc.perform(delete("/api/v1/sessions/project:p:chat:one").header("Authorization", "Bearer secret"))
          .andExpect(status().isMethodNotAllowed());
      mvc.perform(get("/api/v1/sessions/two/turns?limit=1").header("Authorization", "Bearer secret"))
          .andExpect(status().isOk()).andExpect(jsonPath("$.sessionId").value("two"))
          .andExpect(jsonPath("$.items[0].turnId").value("a")).andExpect(jsonPath("$.items[0].runId").value("a"))
          .andExpect(jsonPath("$.items[0].userMessage").value("question a"))
          .andExpect(jsonPath("$.items[0].assistantMessage").value("answer a"))
          .andExpect(jsonPath("$.items[0].createdAt").value(now.toString()))
          .andExpect(jsonPath("$.nextCursor").isString());
      mvc.perform(get("/api/v1/sessions/missing/turns").header("Authorization", "Bearer secret")).andExpect(status().isNotFound());
      for (String query : new String[]{"limit=0", "limit=-1", "limit=101", "limit=abc", "cursor=invalid"})
        mvc.perform(get("/api/v1/sessions/two/turns?" + query).header("Authorization", "Bearer secret")).andExpect(status().isBadRequest());
    });
  }
}
