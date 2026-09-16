package dev.mikoto2000.rei.web;

import dev.mikoto2000.rei.application.project.ProjectQueryService;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ProjectControllerTest {
  @TempDir Path directory;
  @Configuration(proxyBeanMethods = false) @EnableWebMvc
  @Import({SecurityConfig.class, ProjectController.class})
  static class Config {
    @Bean ApiKeyProperties apiKeyProperties() { return new ApiKeyProperties("test-secret"); }
    @Bean ProjectQueryService projectQueryService(ProjectRegistry projects) { return new ProjectQueryService(projects); }
  }

  @Test void authenticatedListReturnsOnlyIdsAndNamesUsableForChat() {
    var file = directory.resolve("projects.json");
    var projects = new ProjectRegistry(file);
    new WebApplicationContextRunner().withPropertyValues("rei.web.enabled=true")
        .withBean(ProjectRegistry.class, () -> projects).withUserConfiguration(Config.class).run(context -> {
      var mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain", jakarta.servlet.Filter.class)).build();
      mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
      mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer wrong"))
          .andExpect(status().isUnauthorized());
      mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer test-secret"))
          .andExpect(status().isOk()).andExpect(content().json("[]"));
      assertThat(file).doesNotExist();

      var first = projects.resolve(Files.createDirectory(directory.resolve("first")));
      var second = projects.resolve(Files.createDirectory(directory.resolve("second")));
      var before = Files.readString(file);
      var result = mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer test-secret"))
          .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
          .andReturn();
      var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(result.getResponse().getContentAsString());
      assertThat(json.size()).isEqualTo(2);
      assertThat(json.get(0).size()).isEqualTo(2);
      assertThat(json.get(0).get("id").asText()).isEqualTo(first.id());
      assertThat(json.get(0).get("name").asText()).isEqualTo("first");
      assertThat(json.get(1).size()).isEqualTo(2);
      assertThat(json.get(1).get("id").asText()).isEqualTo(second.id());
      assertThat(json.get(1).get("name").asText()).isEqualTo("second");
      assertThat(Files.readString(file)).isEqualTo(before);
      var clock = Clock.systemUTC();
      var chat = new ChatSubmitService(projects, new SessionRegistry(clock), new RunRegistry(clock), (c, p) -> {});
      assertThat(chat.submit("hello", json.get(0).get("id").asText(), null).projectId()).isEqualTo(first.id());
      projects.remove(second.root());
      mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer test-secret"))
          .andExpect(jsonPath("$.length()").value(1));
    });
  }
}
