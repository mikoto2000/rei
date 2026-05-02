package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ExternalConfigSupportTest {

  @Test
  void additionalLocationUsesOptionalExternalApplicationYaml() {
    Path workDirectory = Path.of("/work/rei");
    String expected = "optional:file:" + workDirectory.resolve(".rei").resolve("application.yaml");

    assertEquals(expected, ExternalConfigSupport.additionalLocation(workDirectory));
  }
}
