package dev.mikoto2000.rei.interest;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

@Component
@RequiredArgsConstructor
public class LlmInterestTopicExtractor implements InterestTopicExtractor {
  private static final long EXTRACT_TIMEOUT_SECONDS = 1200L;

  private final ChatModel chatModel;
  private final ModelHolderService modelHolderService;
  private final JsonMapper objectMapper;

  @Override
  public List<InterestTopicCandidate> extract(List<ConversationSnippet> snippets, int maxTopics) {
    return extract(snippets, maxTopics, List.of());
  }

  @Override
  public List<InterestTopicCandidate> extract(List<ConversationSnippet> snippets, int maxTopics, List<String> pastQueries) {
    if (snippets.isEmpty()) {
      return List.of();
    }

    Prompt prompt = new Prompt(
        buildPrompt(snippets, maxTopics, pastQueries),
        OpenAiChatOptions.builder()
            .model(modelHolderService.get())
            .build());

    String response = callWithTimeout(prompt);
    return parse(response);
  }

  private String callWithTimeout(Prompt prompt) {
    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<String> future = executor.submit(() -> chatModel.call(prompt).getResult().getOutput().getText());
    try {
      return future.get(EXTRACT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new IllegalStateException("Interest topic extraction timed out after " + EXTRACT_TIMEOUT_SECONDS + " seconds", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interest topic extraction was interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtimeException) {
        throw runtimeException;
      }
      throw new IllegalStateException("Interest topic extraction failed", cause);
    } finally {
      executor.shutdownNow();
    }
  }

  private String buildPrompt(List<ConversationSnippet> snippets, int maxTopics, List<String> pastQueries) {
    String conversation = snippets.stream()
        .map(snippet -> "- [%s] %s".formatted(snippet.createdAt(), snippet.text()))
        .collect(Collectors.joining("\n", "", ""));

    return """
        Extract candidate interest topics from user conversation history for later web search.
        Requirements:
        - Output must be a JSON array only.
        - Maximum %d items.
        - Each item must contain topic, reason, searchQuery, score.
        - score must be between 0.0 and 1.0.
        - Return [] when no candidate exists.
        %s
        Conversation history:
        %s
        """.formatted(maxTopics, buildPastQueriesSection(pastQueries), conversation);
  }

  private String buildPastQueriesSection(List<String> pastQueries) {
    if (pastQueries.isEmpty()) {
      return "";
    }
    String queryList = pastQueries.stream()
        .map(q -> "- " + q)
        .collect(Collectors.joining("\n"));
    return """

        Previously used search queries. Avoid duplicates:
        %s
        """.formatted(queryList);
  }

  private List<InterestTopicCandidate> parse(String response) {
    try {
      return objectMapper.readValue(normalizeJsonArray(response), new TypeReference<List<InterestTopicCandidate>>() {
      });
    } catch (Exception e) {
      throw new IllegalStateException("Failed to parse interest topic candidates", e);
    }
  }

  static String normalizeJsonArray(String response) {
    if (response == null) {
      return "[]";
    }
    String trimmed = response.trim();
    if (trimmed.isEmpty()) {
      return "[]";
    }

    if (trimmed.startsWith("```")) {
      int firstNewline = trimmed.indexOf('\n');
      if (firstNewline >= 0) {
        trimmed = trimmed.substring(firstNewline + 1).trim();
      } else {
        return "[]";
      }
      int closingFence = trimmed.lastIndexOf("```");
      if (closingFence >= 0) {
        trimmed = trimmed.substring(0, closingFence).trim();
      }
    }

    int arrayStart = trimmed.indexOf('[');
    int arrayEnd = trimmed.lastIndexOf(']');
    if (arrayStart >= 0 && arrayEnd >= arrayStart) {
      return trimmed.substring(arrayStart, arrayEnd + 1).trim();
    }
    return trimmed;
  }
}
