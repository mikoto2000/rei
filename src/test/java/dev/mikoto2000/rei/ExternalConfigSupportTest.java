package dev.mikoto2000.rei;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ExternalConfigSupportTest {

  @Test
  void additionalLocationUsesOptionalExternalApplicationYaml() {
    Path workDirectory = Path.of("/work/rei");

    assertEquals(
        "optional:file:/work/rei/.rei/application.yaml",
        ExternalConfigSupport.additionalLocation(workDirectory));
  }
}
