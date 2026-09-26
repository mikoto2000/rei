package dev.mikoto2000.rei.application.session;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.mikoto2000.rei.application.run.*;
import dev.mikoto2000.rei.conversation.FileSessionRepository;
import dev.mikoto2000.rei.core.chat.AgentRunContext;
import dev.mikoto2000.rei.core.project.*;
import static org.assertj.core.api.Assertions.*;

class ShellSessionLifecycleTest {
  @TempDir Path temp;
  final Instant now = Instant.parse("2026-09-17T00:00:00Z");
  @Test void shellCreatesBeforeEnqueueAndContinuesWithImmutableMetadata() {
    var projects = new ProjectService(temp, new ProjectRegistry(temp.resolve("projects.json")));
    var repository = new FileSessionRepository(temp.resolve("sessions.json"));
    var accepted = new ArrayList<AgentRunContext>();
    var lifecycle = new SessionLifecycle(repository, Clock.fixed(now, ZoneOffset.UTC));
    var shell = new ShellConversationService(projects, lifecycle, (context, prompt) -> {
      assertThat(repository.findById(context.conversationId())).isPresent();
      accepted.add(context);
    });
    try (var scope = projects.newClient().open()) {
      var first = shell.submit("😀".repeat(81));
      assertThat(first.requestSource()).isEqualTo(AgentRunContext.RequestSource.SHELL);
      assertThat(first.conversationId()).doesNotEndWith("chat:main");
      assertThat(dev.mikoto2000.rei.llm.ConversationIds.currentChat()).isEqualTo(first.conversationId());
      assertThat(repository.findById(first.conversationId())).contains(new SessionMetadata(first.conversationId(), first.projectId(), "😀".repeat(80), now, now));
      var later = new ShellConversationService(projects, new SessionLifecycle(repository, Clock.fixed(now.plusSeconds(5), ZoneOffset.UTC)), (c,p)->accepted.add(c));
      var next = later.submit("second");
      assertThat(next.conversationId()).isEqualTo(first.conversationId());
      assertThat(next.runId()).isNotEqualTo(first.runId());
      assertThat(repository.findById(first.conversationId())).contains(new SessionMetadata(first.conversationId(), first.projectId(), "😀".repeat(80), now, now.plusSeconds(5)));
      shell.newConversation();
      assertThat(repository.findPage(null,null,100)).hasSize(2);
      assertThat(shell.currentSessionId()).isNotNull().isNotEqualTo(first.conversationId());
      assertThat(projects.currentContext().id()).isEqualTo(first.projectId());
      assertThat(accepted).hasSize(2);
      assertThat(shell.submit("new").conversationId()).isNotEqualTo(first.conversationId());
    }
    try (var restarted = projects.newClient().open()) {
      assertThat(shell.currentSessionId()).isNull();
      shell.submit("restart");
    }
    assertThat(repository.findPage(null,null,100)).hasSize(3);
    assertThat(accepted).hasSize(4);
  }
}
