package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.core.chat.*;
import static org.assertj.core.api.Assertions.*;

class ProjectClientIsolationTest {
  @TempDir Path temp;

  @Test void clientsRetainIndependentSelectionsAcrossReusedWorkerRequests() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    Files.createDirectory(a.resolve("child"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var first = projects.newClient();
    var second = projects.newClient();
    try (var worker = Executors.newSingleThreadExecutor()) {
      worker.submit(() -> { try (var scope = first.open()) { projects.cd(a.toString()); } }).get();
      worker.submit(() -> { try (var scope = second.open()) { projects.cd(b.toString()); } }).get();
      worker.submit(() -> {
        try (var scope = first.open()) {
          assertThat(projects.currentProject()).isEqualTo(a);
          assertThat(ProjectService.contextForOperation().root()).isEqualTo(a);
          assertThat(projects.cd("child")).isEqualTo(a.resolve("child"));
        }
        assertThat(ProjectService.contextForOperation()).isNull();
        assertThatThrownBy(() -> projects.cd(a.toString())).isInstanceOf(IllegalStateException.class);
      }).get();
      worker.submit(() -> {
        try (var scope = second.open()) {
          assertThat(projects.currentProject()).isEqualTo(b);
          assertThat(ProjectService.currentProjectOrStartupDirectory()).isEqualTo(b);
        }
      }).get();
    }
  }

  @Test void parallelClientsResolveStateIndependentlyAndRunOwnershipTakesPrecedence() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var first = projects.newClient();
    var second = projects.newClient();
    var ready = new CyclicBarrier(2);
    try (var workers = Executors.newFixedThreadPool(2)) {
      var one = workers.submit(() -> {
        try (var scope = first.open()) {
          projects.cd(a.toString());
          var owner = projects.currentContext();
          ready.await(5, TimeUnit.SECONDS);
          assertThat(ProjectService.contextForOperation()).isEqualTo(owner);
          assertThat(projects.currentProject()).isEqualTo(a);
          projects.cd(b.toString());
          try (var run = AgentRunScope.open(new AgentRunContext("run", owner, "chat:main"))) {
            assertThat(ProjectService.contextForOperation()).isEqualTo(owner);
            assertThat(projects.currentProject()).isEqualTo(b);
            assertThat(ProjectService.currentProjectOrStartupDirectory()).isEqualTo(b);
          }
          assertThat(projects.currentProject()).isEqualTo(b);
        }
        return null;
      });
      var two = workers.submit(() -> {
        try (var scope = second.open()) {
          projects.cd(b.toString());
          ready.await(5, TimeUnit.SECONDS);
          assertThat(ProjectService.contextForOperation().root()).isEqualTo(b);
        }
        return null;
      });
      one.get(10, TimeUnit.SECONDS);
      two.get(10, TimeUnit.SECONDS);
    }
  }

  @Test void nestedScopesAndFailuresRestoreCallerWithoutChangingOtherClients() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var first = projects.newClient();
    var second = projects.newClient();
    try (var outer = first.open()) {
      projects.cd(a.toString());
      assertThatThrownBy(() -> {
        try (var inner = second.open()) { projects.cd("missing"); }
      }).isInstanceOf(IllegalArgumentException.class);
      assertThat(projects.currentProject()).isEqualTo(a);
      var unrelated = new ProjectService(temp, new ProjectRegistry(temp.resolve("other.json")));
      assertThat(ProjectService.contextForOperation().root()).isEqualTo(a);
      assertThatThrownBy(() -> unrelated.cd(temp.toString())).isInstanceOf(IllegalStateException.class);
      try (var inner = second.open()) {
        assertThat(projects.currentProject()).isEqualTo(temp);
        projects.remove(a.toString());
      }
      assertThat(projects.currentProject()).isEqualTo(a);
    }
    assertThat(ProjectService.contextForOperation()).isNull();
  }
}
