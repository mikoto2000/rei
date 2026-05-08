package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.CommandCancellationService;
import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.search.SearchKnowledgeResult;
import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchPage;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class SearchCommandTest {

  @Test
  void searchCommandCombinesVectorStoreAndWebResults() throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    SearchKnowledgeService searchKnowledgeService = Mockito.mock(SearchKnowledgeService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(searchKnowledgeService.search("spring ai", 3, 2, 0.4d, "/tmp/docs/spec.md")).thenReturn(new SearchKnowledgeResult(
        "spring ai",
        List.of(new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")),
        new WebSearchContext(
            List.of(new WebSearchPage("Spring AI Docs", "https://docs.example.com/news", "latest update", "2026-03-31", "Spring AI fetched content")),
            List.of(new WebSearchPage("Blog", "https://example.com/blog", "blog snippet", null, "Less trusted content"))),
        null));
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.just("combined ", "answer"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new SearchCommand(chatClient, modelHolderService, searchKnowledgeService, cancellationService))
          .execute("--vector-top-k", "3", "--web-top-k", "2", "--threshold", "0.4", "--source", "/tmp/docs/spec.md", "spring ai");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(searchKnowledgeService).search("spring ai", 3, 2, 0.4d, "/tmp/docs/spec.md");

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient).prompt(promptCaptor.capture());
    String promptText = promptCaptor.getValue().getContents();
    assertTrue(promptText.contains("Spring AI guide"));
    assertTrue(promptText.contains("https://docs.example.com/news"));
    assertTrue(promptText.contains("latest update"));
    assertTrue(promptText.contains("Spring AI fetched content"));
    assertTrue(promptText.contains("Web 一次情報:"));
    assertTrue(promptText.contains("Web 補足情報:"));
    assertTrue(promptText.contains("Less trusted content"));

    String output = out.toString();
    assertTrue(output.contains("=== answer("));
    assertTrue(output.contains(" s) ==="));
    assertTrue(output.contains("combined answer"));
    assertTrue(output.contains("=== sources ==="));
    assertTrue(output.contains("https://example.com/blog"));
  }

  @Test
  void searchCommandFallsBackToVectorStoreWhenWebSearchIsDisabled() throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    SearchKnowledgeService searchKnowledgeService = Mockito.mock(SearchKnowledgeService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(searchKnowledgeService.search("spring ai", 3, 5, null, null)).thenReturn(new SearchKnowledgeResult(
        "spring ai",
        List.of(new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")),
        WebSearchContext.primaryOnly(List.of()),
        "Web search is disabled"));
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.just("vector ", "only"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new SearchCommand(chatClient, modelHolderService, searchKnowledgeService, cancellationService))
          .execute("spring ai");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient).prompt(promptCaptor.capture());
    String promptText = promptCaptor.getValue().getContents();
    assertTrue(promptText.contains("Spring AI guide"));
    assertTrue(promptText.contains("Web 一次情報:"));
    assertTrue(promptText.contains("Web 補足情報:"));
    assertTrue(promptText.contains("該当なし"));

    String output = out.toString();
    assertTrue(output.contains("vector only"));
    assertTrue(output.contains("=== sources ==="));
    assertTrue(output.contains("/tmp/docs/spec.md"));
    assertTrue(output.contains("[web search skipped]"));
  }

  @Test
  void searchCommandFallsBackToVectorStoreWhenWebSearchApiKeyIsInvalid() throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    SearchKnowledgeService searchKnowledgeService = Mockito.mock(SearchKnowledgeService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(searchKnowledgeService.search("spring ai", 3, 5, null, null)).thenReturn(new SearchKnowledgeResult(
        "spring ai",
        List.of(new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")),
        WebSearchContext.primaryOnly(List.of()),
        "Web search failed with status 401: invalid api key"));
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.just("vector ", "fallback"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new SearchCommand(chatClient, modelHolderService, searchKnowledgeService, cancellationService))
          .execute("spring ai");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient).prompt(promptCaptor.capture());
    String promptText = promptCaptor.getValue().getContents();
    assertTrue(promptText.contains("Spring AI guide"));
    assertTrue(promptText.contains("Web 一次情報:"));
    assertTrue(promptText.contains("Web 補足情報:"));
    assertTrue(promptText.contains("該当なし"));

    String output = out.toString();
    assertTrue(output.contains("vector fallback"));
    assertTrue(output.contains("[web search skipped] Web search failed with status 401"));
  }

  @Test
  void searchCommandStopsWaitingWhenStreamDoesNotTerminate() throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    SearchKnowledgeService searchKnowledgeService = Mockito.mock(SearchKnowledgeService.class);
    CommandCancellationService cancellationService = new CommandCancellationService();
    AtomicBoolean disposed = new AtomicBoolean(false);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(searchKnowledgeService.search("spring ai", 3, 5, null, null)).thenReturn(new SearchKnowledgeResult(
        "spring ai",
        List.of(new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")),
        WebSearchContext.primaryOnly(List.of()),
        null));
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.<String>never().doOnCancel(() -> disposed.set(true)));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      SearchCommand command = new SearchCommand(chatClient, modelHolderService, searchKnowledgeService, cancellationService) {
        @Override
        long streamTimeoutMillis() {
          return 1L;
        }
      };
      int exitCode = new CommandLine(command).execute("spring ai");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    String output = out.toString();
    assertTrue(output.contains("[error] 回答の取得がタイムアウトしました"));
    assertTrue(disposed.get());
  }

  @Test
  void rootCommandIncludesSearchSubcommand() {
    CommandLine commandLine = new CommandLine(new RootCommand());
    assertTrue(commandLine.getSubcommands().containsKey("search"));
  }
}
