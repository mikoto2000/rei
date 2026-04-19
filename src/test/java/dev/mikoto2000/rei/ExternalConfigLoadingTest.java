package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

class ExternalConfigLoadingTest {

  @TempDir
  Path tempDir;

  @Test
  void externalApplicationYamlOverridesClasspathDefaults() throws Exception {
    Path configFile = tempDir.resolve(".rei").resolve("application.yaml");
    Files.createDirectories(configFile.getParent());
    Files.writeString(configFile, """
        rei:
          web-search:
            enabled: false
        """);

    SpringApplication application = new SpringApplication(TestConfiguration.class);
    application.setDefaultProperties(ExternalConfigSupport.defaultProperties(tempDir));

    try (ConfigurableApplicationContext context = application.run("--spring.main.web-application-type=none")) {
      assertEquals("false", context.getEnvironment().getProperty("rei.web-search.enabled"));
    }
  }

  @Configuration
  static class TestConfiguration {
  }
}
