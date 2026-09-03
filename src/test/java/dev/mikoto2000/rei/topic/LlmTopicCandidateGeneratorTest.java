package dev.mikoto2000.rei.topic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import tools.jackson.databind.json.JsonMapper;

class LlmTopicCandidateGeneratorTest {

  @Test
  void usesLlmResponseWithoutCallingExternalApiInUnitTest() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    when(modelHolderService.get()).thenReturn("model");
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
        new Generation(new AssistantMessage("""
            {"candidates":[{"topic":"測定","reason":"未実施","type":"unfinished_work","source":"conversation","priority":0.8,"freshness":0.8,"usefulness":0.9,"intrusiveness":0.1,"confidence":0.9}]}
            """)))));
    LlmTopicCandidateGenerator generator = new LlmTopicCandidateGenerator(chatModel, modelHolderService,
        new TopicCandidateParser(new JsonMapper(), Clock.fixed(Instant.parse("2026-09-02T00:00:00Z"), ZoneOffset.UTC)),
        properties);

    var candidates = generator.generate(context());

    assertEquals(1, candidates.size());
    assertEquals("測定", candidates.getFirst().topic());
  }

  @Test
  void llmTimeoutFailsClosed() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    ModelHolderService modelHolderService = org.mockito.Mockito.mock(ModelHolderService.class);
    TopicGeneratorProperties properties = new TopicGeneratorProperties();
    properties.setEnabled(true);
    when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("timeout"));
    LlmTopicCandidateGenerator generator = new LlmTopicCandidateGenerator(chatModel, modelHolderService,
        new TopicCandidateParser(new JsonMapper(), Clock.systemUTC()), properties);

    assertTrue(generator.generate(context()).isEmpty());
  }

  @Test
  void disabledDoesNotCallLlm() {
    ChatModel chatModel = org.mockito.Mockito.mock(ChatModel.class);
    LlmTopicCandidateGenerator generator = new LlmTopicCandidateGenerator(chatModel, null,
        new TopicCandidateParser(new JsonMapper(), Clock.systemUTC()), new TopicGeneratorProperties());

    assertTrue(generator.generate(context()).isEmpty());
  }

  private TopicGenerationContext context() {
    return new TopicGenerationContext(
        List.of(new ConversationTopicMessage("user", "効果測定はまだ", Instant.parse("2026-09-02T00:00:00Z"))),
        List.of(),
        Instant.parse("2026-09-02T00:00:00Z"),
        List.of());
  }
}
