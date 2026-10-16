package dev.mikoto2000.rei.core.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@org.junit.jupiter.api.Tag("integration")
class ProjectRegistryTest {
  @TempDir Path temp;

  @Test
  void identitySurvivesReloadAndExplicitRelocation() throws Exception {
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

  @Test
  void failedWriteDoesNotPublishAnUnpersistedIdentity() throws Exception {
    var file = Files.writeString(temp.resolve("not-directory"), "block");
    var registry = new ProjectRegistry(file.resolve("projects.json"));
    assertThatThrownBy(() -> registry.resolve(temp)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void separateShellAndWebRegistriesDoNotLoseConcurrentRegistrations() throws Exception {
    var file = temp.resolve("data/projects.json");
    var shell = new ProjectRegistry(file);
    var web = new ProjectRegistry(file.toAbsolutePath());
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var registrations = new java.util.ArrayList<java.util.concurrent.Future<ProjectContext>>();
      for (int index = 0; index < 48; index++) {
        Path directory = Files.createDirectory(temp.resolve("project-" + index));
        var registry = index % 2 == 0 ? shell : web;
        registrations.add(
            workers.submit(
                () -> {
                  start.await();
                  return registry.resolve(directory);
                }));
      }
      start.countDown();
      var identities = new java.util.HashSet<String>();
      for (var registration : registrations) identities.add(registration.get().id());
      assertThat(new ProjectRegistry(file).list()).hasSize(48);
      assertThat(identities).hasSize(48);
    }
  }
}
