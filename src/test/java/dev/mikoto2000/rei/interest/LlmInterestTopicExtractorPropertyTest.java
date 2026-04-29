package dev.mikoto2000.rei.interest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feature: interest-discovery
 * Property 1: 過去クエリがプロンプトに含まれる
 *
 * 任意の過去クエリリストに対して、buildPrompt() が生成するプロンプト文字列に
 * すべてのクエリが含まれることを検証する。
 */
class LlmInterestTopicExtractorPropertyTest {

  // テスト用の過去クエリセット（様々なパターン）
  static Stream<List<String>> pastQueriesVariants() {
    return Stream.of(
        List.of("Neovim devcontainer best practices"),
        List.of("vim plugin 2025", "neovim lsp setup"),
        List.of("Java 25 features", "Spring Boot 4 migration", "GraalVM native image"),
        List.of("rust async programming"),
        List.of("docker compose v2 tips", "kubernetes helm charts", "terraform modules"),
        List.of("machine learning pytorch", "llm fine tuning", "rag architecture", "vector database"),
        List.of("git rebase workflow"),
        List.of("typescript generics advanced", "react server components"),
        List.of("linux kernel 6.x features", "ebpf programming"),
        List.of("postgresql performance tuning", "redis cluster setup")
    );
  }

  @ParameterizedTest(name = "pastQueries={0}")
  @MethodSource("pastQueriesVariants")
  @Tag("interest-discovery-property-1-pastQueriesInPrompt")
  void allPastQueriesAreIncludedInPrompt(List<String> pastQueries) {
    ChatModel chatModel = mock(ChatModel.class);
    ModelHolderService modelHolderService = mock(ModelHolderService.class);
    LlmInterestTopicExtractor extractor = new LlmInterestTopicExtractor(chatModel, modelHolderService, new JsonMapper());

    when(modelHolderService.get()).thenReturn("test-model");
    when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
        new Generation(new AssistantMessage("[]")))));

    extractor.extract(
        List.of(new ConversationSnippet("c1", "テスト会話", OffsetDateTime.now(ZoneOffset.UTC))),
        3,
        pastQueries);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatModel).call(promptCaptor.capture());
    String prompt = promptCaptor.getValue().getContents();

    // すべての過去クエリがプロンプトに含まれること
    for (String query : pastQueries) {
      assertTrue(prompt.contains(query),
          "過去クエリ「" + query + "」がプロンプトに含まれるべき");
    }
  }
}
