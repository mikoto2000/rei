package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.core.working.WorkingSet;

class ProjectBeanScopeTest {
  @TempDir Path temp;
  @Test void workingSetIsScopedAndPersistsIndependentlyOfEvents() throws Exception {
    Path a = Files.createDirectory(temp.resolve("a"));
    Path b = Files.createDirectory(temp.resolve("b"));
    var service = new ProjectService(a, new ProjectRegistry(temp.resolve("projects.json")));
    var scope = new ProjectBeanScope();
    var first = (WorkingSet) scope.get("workingSet", WorkingSet::new);
    first.enablePersistence(temp.resolve("state/files.json"));
    first.recordRead(a.resolve("A.java"));
    var run = new AgentRunContext("run", service.currentContext(), "chat:main");
    service.cd(b.toString());
    assertThat(((WorkingSet) scope.get("workingSet", WorkingSet::new)).getFiles()).isEmpty();
    try (var binding = AgentRunScope.open(run)) {
      assertThat(scope.get("workingSet", WorkingSet::new)).isSameAs(first);
    }
    service.cd(a.toString());
    assertThat(scope.get("workingSet", WorkingSet::new)).isSameAs(first);
    var reloaded = new WorkingSet();
    reloaded.enablePersistence(temp.resolve("state/files.json"));
    assertThat(reloaded.getFiles()).isEqualTo(first.getFiles());
  }
}
