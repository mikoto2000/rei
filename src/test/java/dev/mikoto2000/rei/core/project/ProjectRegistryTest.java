package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class ProjectRegistryTest {
  @TempDir Path temp;
  @Test void identitySurvivesReloadAndExplicitRelocation() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var file = temp.resolve("data/projects.json");
    var registry = new ProjectRegistry(file);
    var first = registry.resolve(a);
    assertThat(new ProjectRegistry(file).resolve(a.resolve("."))).isEqualTo(first);
    registry.relocate(first.id(), b);
    assertThat(new ProjectRegistry(file).resolve(b).id()).isEqualTo(first.id());
    assertThat(Files.exists(a.resolve(".rei"))).isFalse();
    assertThat(Files.readString(file)).contains("\"projects\"", first.id());
  }
  @Test void failedWriteDoesNotPublishAnUnpersistedIdentity() throws Exception {
    var file = Files.writeString(temp.resolve("not-directory"), "block");
    var registry = new ProjectRegistry(file.resolve("projects.json"));
    assertThatThrownBy(() -> registry.resolve(temp)).isInstanceOf(IllegalStateException.class);
  }
}
