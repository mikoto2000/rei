package dev.mikoto2000.rei.subagent;

import static org.assertj.core.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class SubAgentCommandTest {
  @TempDir Path directory;
  @Test void listShowReloadValidateAndInit() throws Exception {
    Files.writeString(directory.resolve("reviewer.yaml"), SubAgentConfigurationTest.yaml("reviewer"));
    var policy = new SubAgentToolPolicy(Set.of("readMultiFile"));
    var loader = new SubAgentDefinitionLoader(policy, m -> true);
    var registry = new SubAgentRegistry(directory, loader);
    registry.reload();
    var command = new CommandLine(new SubAgentCommand(registry, loader, policy));
    var output = new StringWriter();
    command.setOut(new PrintWriter(output));
    assertThat(command.execute()).isZero();
    assertThat(output.toString()).contains("reviewer", "Reviewer", "Independent review");
    output.getBuffer().setLength(0);
    assertThat(command.execute("list")).isZero();
    assertThat(output.toString()).contains("reviewer");
    output.getBuffer().setLength(0);
    assertThat(command.execute("show", "reviewer")).isZero();
    assertThat(output.toString()).contains("requested tools", "effective tools", "model", "maxSteps", "timeout", "reviewer.yaml")
        .doesNotContain("Review independently.");
    assertThat(command.execute("validate", directory.resolve("reviewer.yaml").toString())).isZero();
    assertThat(command.execute("init", "java-expert")).isZero();
    assertThat(loader.load(directory.resolve("java-expert.yaml")).id()).isEqualTo("java-expert");
    assertThat(command.execute("init", "java-expert")).isNotZero();
    assertThat(command.execute("init", "../outside")).isNotZero();
    assertThat(command.execute("reload")).isZero();
    assertThat(registry.list()).hasSize(2);
    Files.writeString(directory.resolve("bad.yaml"), "id: [bad]");
    assertThat(command.execute("reload")).isNotZero();
    assertThat(registry.list()).hasSize(2);
    assertThat(command.execute("validate", directory.resolve("bad.yaml").toString())).isNotZero();
    assertThat(command.execute("show", "missing")).isNotZero();
  }
}
