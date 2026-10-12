package dev.mikoto2000.rei.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.mikoto2000.rei.dot.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.*;

@Tag("integration")
class DotHttpTest {
  @TempDir Path root;

  @Configuration(proxyBeanMethods = false)
  @Import({WebApiIntegrationTest.Config.class, DotController.class,
      dev.mikoto2000.rei.subagent.DelegationToolPermissionResolver.class})
  static class Config {
    @Bean
    DotDelegationService dots() {
      return mock(DotDelegationService.class);
    }

    @Bean
    dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository dependencies() {
      return mock(dev.mikoto2000.rei.core.dependency.PersistentDependencyRepository.class);
    }

    @Bean
    dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository checkpoints() {
      return mock(dev.mikoto2000.rei.checkpoint.PersistentCheckpointRepository.class);
    }

    @Bean
    dev.mikoto2000.rei.core.policy.ToolPermissionPolicy policy() {
      return new dev.mikoto2000.rei.core.policy.ToolPermissionPolicy(
          new dev.mikoto2000.rei.core.policy.ToolPermissionProperties(false, null, null, null));
    }

    @Bean
    DotResumeService resumes() {
      return mock(DotResumeService.class);
    }
  }

  @Test
  void bearerAuthAndStrictFixedWriteDtosApplyToDotRoutes() throws Exception {
    var app = new SpringApplication(Config.class);
    WebApplication.configure(app, "integration-key");
    try (var context =
            app.run(
                "--rei.web.port=0",
                "--rei.data-dir=" + root,
                "--logging.config=classpath:web-test-logback.xml");
        var client = HttpClient.newHttpClient()) {
      int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
      String base = "/projects/project/dot-requests";
      var dots = context.getBean(DotDelegationService.class);
      var resumes = context.getBean(DotResumeService.class);
      reset(dots, resumes);
      when(dots.createHuman(eq("project"), isNull(), anyMap()))
          .thenReturn(Map.of("status", "PENDING"));
      when(resumes.verify(eq("project"), eq("id"), anyMap()))
          .thenReturn(Map.of("status", "VERIFIED"));
      assertEquals(
          401,
          ReadHttpTest.send(client, port, base, "POST", "{\"instruction\":\"research\"}", false)
              .statusCode());
      verifyNoInteractions(dots, resumes);
      assertEquals(
          200,
          ReadHttpTest.send(
                  client,
                  port,
                  base,
                  "POST",
                  "{\"instruction\":\"research\",\"timeout\":3600}",
                  true)
              .statusCode());
      assertEquals(
          400,
          ReadHttpTest.send(
                  client,
                  port,
                  base,
                  "POST",
                  "{\"instruction\":\"research\",\"root\":\"forged\"}",
                  true)
              .statusCode());
      assertEquals(
          401,
          ReadHttpTest.send(
                  client,
                  port,
                  base + "/id/verify",
                  "POST",
                  "{\"confirmed\":true,\"status\":\"VERIFIED\",\"note\":\"checked\"}",
                  false)
              .statusCode());
      verifyNoInteractions(resumes);
      assertEquals(
          200,
          ReadHttpTest.send(
                  client,
                  port,
                  base + "/id/verify",
                  "POST",
                  "{\"confirmed\":true,\"status\":\"VERIFIED\",\"note\":\"checked\",\"satisfied\":[0]}",
                  true)
              .statusCode());
      verify(resumes)
          .verify(
              eq("project"),
              eq("id"),
              argThat(
                  a ->
                      Boolean.TRUE.equals(a.get("confirmed"))
                          && a.get("satisfied").equals(List.of(0))));
      assertEquals(
          401,
          ReadHttpTest.send(
                  client, port, base + "/id/resume", "POST", "{\"confirmed\":true}", false)
              .statusCode());
      verify(resumes, never()).resume(anyString(), anyString(), anyMap());
    }
  }
}
