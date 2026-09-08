package dev.mikoto2000.rei.core.project;

import java.nio.file.*;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import dev.mikoto2000.rei.core.chat.*;
import dev.mikoto2000.rei.conversation.*;

class ProjectStoresTest {
  @TempDir Path temp;
  @Test void logsAndSearchStayWithOwnerAfterSwitchAndReload() throws Exception {
    String previous = System.getProperty("rei.data-dir");
    System.setProperty("rei.data-dir", temp.resolve("data").toString());
    try {
      Path a = Files.createDirectory(temp.resolve("a"));
      Path b = Files.createDirectory(temp.resolve("b"));
      var service = new ProjectService(a, new ProjectRegistry(temp.resolve("data/projects.json")));
      var owner = service.currentContext();
      var run = new AgentRunContext("run", owner, "chat:main");
      var activity = new dev.mikoto2000.rei.event.ProfileEventLogStore();
      var store = new ConversationLogStore();
      service.cd(b.toString());
      var other = service.currentContext();
      activity.append(new dev.mikoto2000.rei.event.AgentEventFactory(Clock.systemUTC())
          .runCompleted("run", 1, null, null, null, null).withOwnership(run));
      assertThat(activity.readAll()).isEmpty();
      store.append(other.conversationId("chat:main"), "user", "only B");
      try (var scope = AgentRunScope.open(run)) {
        store.append(run.conversationId(), "user", "only A");
        assertThat(store.readAll()).extracting(ConversationLogEntry::content).containsExactly("only A");
      }
      assertThat(store.readAll()).extracting(ConversationLogEntry::content).containsExactly("only B");
      service.cd(a.toString());
      assertThat(activity.readAll()).hasSize(1);
      assertThat(new ConversationLogStore().readAll()).extracting(ConversationLogEntry::content).containsExactly("only A");
      assertThat(Files.exists(a.resolve(".rei"))).isFalse();
      assertThat(Files.exists(temp.resolve("data/projects/" + owner.id() + "/conversations"))).isTrue();
    } finally {
      if (previous == null) System.clearProperty("rei.data-dir"); else System.setProperty("rei.data-dir", previous);
    }
  }
}
