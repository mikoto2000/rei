package dev.mikoto2000.rei.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.mikoto2000.rei.application.project.ProjectQueryService;
import dev.mikoto2000.rei.application.run.ChatSubmitService;
import dev.mikoto2000.rei.application.run.RunRegistry;
import dev.mikoto2000.rei.application.run.SessionRegistry;
import dev.mikoto2000.rei.core.project.ProjectRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

/** プロジェクト受付窓口の結果と状態の契約を検証するテスト。 */
@org.junit.jupiter.api.Tag("integration")
class ProjectControllerTest {
  /** 保存や復元を既存データから独立して検証するための一時ディレクトリ。 */
  @TempDir Path directory;

  /** ファイル一覧を保持することを確認する。 */
  @Test
  void removalOnlyUnregistersTheExactProjectAndPreservesFiles() throws Exception {
    var registry = new ProjectRegistry(directory.resolve("projects.json"));
    var root = Files.createDirectory(directory.resolve("root"));
    var content = Files.writeString(root.resolve("keep.txt"), "keep");
    var registered = registry.resolve(root);
    var controller = new ProjectController(new ProjectQueryService(registry));
    controller.setRegistry(registry);
    org.junit.jupiter.api.Assertions.assertThrows(
        dev.mikoto2000.rei.application.run.ResourceNotFoundException.class,
        () -> controller.remove("unknown"));
    controller.remove(registered.id());
    assertThat(registry.list()).isEmpty();
    assertThat(Files.readString(content)).isEqualTo("keep");
  }

  /** 検証対象に必要な依存先と設定を組み立てるテスト用構成。 */
  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @Import({SecurityConfig.class, ProjectController.class})
  static class Config {
    /** {@code apiKeyProperties} の条件を再現するための補助情報を返す。 */
    @Bean
    ApiKeyProperties apiKeyProperties() {
      return new ApiKeyProperties("test-secret");
    }

    /** {@code projectQueryService} の条件を再現するための補助情報を返す。 */
    @Bean
    ProjectQueryService projectQueryService(ProjectRegistry projects) {
      return new ProjectQueryService(projects);
    }
  }

  /** 識別子一覧利用可能を返すことを確認する。 */
  @Test
  void authenticatedListDistinguishesSameNamedProjectsByPathAndReturnsIdsUsableForChat() {
    var file = directory.resolve("projects.json");
    var projects = new ProjectRegistry(file);
    new WebApplicationContextRunner()
        .withPropertyValues("rei.web.enabled=true")
        .withBean(ProjectRegistry.class, () -> projects)
        .withUserConfiguration(Config.class)
        .run(
            context -> {
              var mvc =
                  MockMvcBuilders.webAppContextSetup(context)
                      .addFilters(
                          context.getBean(
                              "springSecurityFilterChain", jakarta.servlet.Filter.class))
                      .build();
              mvc.perform(get("/api/v1/projects")).andExpect(status().isUnauthorized());
              mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer wrong"))
                  .andExpect(status().isUnauthorized());
              mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer test-secret"))
                  .andExpect(status().isOk())
                  .andExpect(content().json("[]"));
              assertThat(file).doesNotExist();

              var first = projects.resolve(Files.createDirectories(directory.resolve("first/rei")));
              var second =
                  projects.resolve(Files.createDirectories(directory.resolve("second/rei")));
              var before = Files.readString(file);
              var result =
                  mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer test-secret"))
                      .andExpect(status().isOk())
                      .andExpect(content().contentTypeCompatibleWith("application/json"))
                      .andReturn();
              var json =
                  new com.fasterxml.jackson.databind.ObjectMapper()
                      .readTree(result.getResponse().getContentAsString());
              assertThat(json.size()).isEqualTo(2);
              assertThat(json.get(0).path("path").asText()).isEqualTo(first.root().toString());
              assertThat(json.get(0).size()).isEqualTo(3);
              assertThat(json.get(0).get("id").asText()).isEqualTo(first.id());
              assertThat(json.get(0).get("name").asText()).isEqualTo("rei");
              assertThat(json.get(1).path("path").asText()).isEqualTo(second.root().toString());
              assertThat(json.get(1).size()).isEqualTo(3);
              assertThat(json.get(1).get("id").asText()).isEqualTo(second.id());
              assertThat(json.get(1).get("name").asText()).isEqualTo("rei");
              assertThat(Files.readString(file)).isEqualTo(before);
              var clock = Clock.systemUTC();
              var chat =
                  new ChatSubmitService(
                      projects,
                      new SessionRegistry(clock),
                      new RunRegistry(clock),
                      new dev.mikoto2000.rei.conversation.FileSessionRepository(
                          directory.resolve("sessions.json")),
                      clock,
                      (c, p) -> {});
              assertThat(chat.submit("hello", json.get(0).get("id").asText(), null).projectId())
                  .isEqualTo(first.id());
              projects.remove(second.root());
              mvc.perform(get("/api/v1/projects").header("Authorization", "Bearer test-secret"))
                  .andExpect(jsonPath("$.length()").value(1));
            });
  }
}
