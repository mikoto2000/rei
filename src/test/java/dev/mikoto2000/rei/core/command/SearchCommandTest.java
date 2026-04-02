package dev.mikoto2000.rei.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.prompt.Prompt;

import dev.mikoto2000.rei.core.service.ModelHolderService;
import dev.mikoto2000.rei.vectordocument.VectorDocumentSearchResult;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import dev.mikoto2000.rei.websearch.WebSearchResult;
import dev.mikoto2000.rei.websearch.WebSearchService;
import picocli.CommandLine;
import reactor.core.publisher.Flux;

class SearchCommandTest {

  @Test
  void searchCommandCombinesVectorStoreAndWebResults() throws Exception {
    ChatClient chatClient = Mockito.mock(ChatClient.class);
    ChatClientRequestSpec requestSpec = Mockito.mock(ChatClientRequestSpec.class, Mockito.RETURNS_DEEP_STUBS);
    ModelHolderService modelHolderService = Mockito.mock(ModelHolderService.class);
    VectorDocumentService vectorDocumentService = Mockito.mock(VectorDocumentService.class);
    WebSearchService webSearchService = Mockito.mock(WebSearchService.class);

    when(modelHolderService.get()).thenReturn("gpt-test");
    when(vectorDocumentService.search("spring ai", 3, 0.4d, "/tmp/docs/spec.md")).thenReturn(List.of(
        new VectorDocumentSearchResult("doc-1", "/tmp/docs/spec.md", 0, 0.91d, "Spring AI guide")));
    when(webSearchService.search("spring ai", 2)).thenReturn(List.of(
        new WebSearchResult("Spring AI News", "https://example.com/news", "latest update", "2026-03-31")));
    when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
    when(requestSpec.stream().content()).thenReturn(Flux.just("combined ", "answer"));

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(out));
    try {
      int exitCode = new CommandLine(new SearchCommand(chatClient, modelHolderService, vectorDocumentService, webSearchService))
          .execute("--vector-top-k", "3", "--web-top-k", "2", "--threshold", "0.4", "--source", "/tmp/docs/spec.md", "spring ai");
      assertEquals(0, exitCode);
    } finally {
      System.setOut(originalOut);
    }

    verify(vectorDocumentService).search("spring ai", 3, 0.4d, "/tmp/docs/spec.md");
    verify(webSearchService).search("spring ai", 2);

    ArgumentCaptor<Prompt> promptCaptor = ArgumentCaptor.forClass(Prompt.class);
    verify(chatClient).prompt(promptCaptor.capture());
    String promptText = promptCaptor.getValue().getContents();
    assertTrue(promptText.contains("Spring AI guide"));
    assertTrue(promptText.contains("https://example.com/news"));
    assertTrue(promptText.contains("latest update"));

    String output = out.toString();
    assertTrue(output.contains("=== answer ==="));
    assertTrue(output.contains("combined answer"));
    assertTrue(output.contains("=== sources ==="));
  }

  @Test
  void rootCommandIncludesSearchSubcommand() {
    CommandLine commandLine = new CommandLine(new RootCommand());
    assertTrue(commandLine.getSubcommands().containsKey("search"));
  }
}
