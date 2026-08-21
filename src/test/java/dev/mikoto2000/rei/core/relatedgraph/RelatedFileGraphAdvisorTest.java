package dev.mikoto2000.rei.core.relatedgraph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.working.WorkingSet;

class RelatedFileGraphAdvisorTest {

  private static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

  @Test
  void relatedFilesForWorkingSetAreInjected() {
    RelatedFileGraph graph = new RelatedFileGraph(100, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    String controller = Path.of("src/UserController.java").toAbsolutePath().normalize().toString();
    String service = Path.of("src/UserService.java").toAbsolutePath().normalize().toString();
    graph.addRelation(controller, service, "REFERENCES", "SEARCH");

    WorkingSet workingSet = new WorkingSet();
    workingSet.recordRead(Path.of("src/UserController.java"));

    RelatedFileGraphAdvisor advisor = new RelatedFileGraphAdvisor(graph, workingSet);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    String text = result.prompt().getUserMessage().getText();
    assertTrue(text.contains("## Related Files"));
    assertTrue(text.contains(controller));
    assertTrue(text.contains(service));
    assertTrue(text.contains("hello"));
  }

  @Test
  void unrelatedRelationsAreNotInjected() {
    RelatedFileGraph graph = new RelatedFileGraph(100, Clock.fixed(Instant.parse("2026-08-17T00:00:00Z"), ZONE));
    graph.addRelation("src/Other.java", "src/Unrelated.java", "REFERENCES", "SEARCH");

    WorkingSet workingSet = new WorkingSet();
    workingSet.recordRead(Path.of("src/UserController.java"));

    RelatedFileGraphAdvisor advisor = new RelatedFileGraphAdvisor(graph, workingSet);
    UserMessage userMessage = UserMessage.builder().text("hello").build();
    Prompt prompt = new Prompt(List.of(userMessage));
    ChatClientRequest request = ChatClientRequest.builder().prompt(prompt).build();

    ChatClientRequest result = advisor.before(request, new NoopAdvisorChain());

    assertEquals("hello", result.prompt().getUserMessage().getText());
  }

  private static final class NoopAdvisorChain implements AdvisorChain {
    @Override
    public io.micrometer.observation.ObservationRegistry getObservationRegistry() {
      return io.micrometer.observation.ObservationRegistry.NOOP;
    }
  }
}
