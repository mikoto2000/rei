package dev.mikoto2000.rei.paper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@org.junit.jupiter.api.Tag("integration")
class PaperFailureTest {
  @Test
  void llmAcceptsMetadataOnlyStreamChunksWithoutToolsOrMemory() {
    var provider = mock(dev.mikoto2000.rei.llm.LlmModelProvider.class);
    var model = mock(org.springframework.ai.chat.model.ChatModel.class);
    when(provider.subAgentChatModel()).thenReturn(model);
    when(provider.model(eq("chat"), nullable(String.class))).thenReturn("test");
    when(provider.chatOptions("chat", "test"))
        .thenReturn(
            org.springframework.ai.openai.OpenAiChatOptions.builder().model("test").build());
    var empty = mock(org.springframework.ai.chat.model.ChatResponse.class, RETURNS_DEEP_STUBS);
    when(empty.getResult().getOutput().getText()).thenReturn(null);
    var text =
        new org.springframework.ai.chat.model.ChatResponse(
            List.of(
                new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage("result"))));
    when(model.stream(any(org.springframework.ai.chat.prompt.Prompt.class)))
        .thenReturn(reactor.core.publisher.Flux.just(empty, text));
    assertEquals(
        "result",
        new SpringPaperLanguageModel(provider, new PaperProperties())
            .generate("system", "source", PaperOperation.local("s")));
  }

  @Test
  void terminologyMustAppearInTranslation() {
    assertThrows(
        PaperException.class,
        () ->
            PaperTranslationService.verifyTerminology(
                "grounding", "別訳", Map.of("grounding", "グラウンディング")));
    assertThrows(
        PaperException.class,
        () -> PaperTranslationService.verifyTerminology("OSWorld and Qwen2.5-VL", "省略", Map.of()));
    assertDoesNotThrow(
        () ->
            PaperTranslationService.verifyTerminology(
                "OSWorld grounding", "OSWorld のグラウンディング", Map.of("grounding", "グラウンディング")));
  }

  @TempDir Path dir;

  @Test
  void databaseDeleteFailureRollsBackVersionRemovalAndRemainsRetryable() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("db"));
    var repo = new SqlitePaperRepository(ds);
    var jdbc = new JdbcTemplate(ds);
    var op = PaperOperation.local("s");
    var p =
        repo.save(
            new Paper(
                null, "Test", List.of(), null, null, null, null, null, null, null, null, null, null,
                List.of()),
            op);
    repo.saveArtifact(p.id(), "summary", "key", "result", op);
    jdbc.execute(
        "CREATE TRIGGER fail_delete BEFORE DELETE ON papers BEGIN SELECT RAISE(ABORT,'test'); END");
    var lib =
        new PaperLibraryService(
            repo,
            new PaperArtifactStore(dir, new PaperProperties()),
            new PaperSessionReferences(),
            new PaperProperties());
    assertThrows(PaperException.class, () -> lib.remove(p.id(), true, op));
    assertEquals("result", repo.artifact(p.id(), "summary", "key").orElseThrow());
    assertTrue(repo.find(p.id()).isEmpty());
    jdbc.execute("DROP TRIGGER fail_delete");
    lib.remove(p.id(), true, op);
    assertTrue(repo.artifact(p.id(), "summary", "key").isEmpty());
  }

  @Test
  void failedInitialPurgeTransactionDoesNotTouchFiles() {
    var ds = new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("db"));
    var repo = new SqlitePaperRepository(ds);
    var op = PaperOperation.local("s");
    var p =
        repo.save(
            new Paper(
                null, "Test", List.of(), null, null, null, null, null, null, null, null, null, null,
                List.of()),
            op);
    var store = new PaperArtifactStore(dir, new PaperProperties());
    store.write(p.id(), "originals", "pdf", new byte[] {1}, op);
    new JdbcTemplate(ds)
        .execute(
            "CREATE TRIGGER fail_update BEFORE UPDATE ON papers BEGIN SELECT RAISE(ABORT,'test');"
                + " END");
    assertThrows(
        PaperException.class,
        () ->
            new PaperLibraryService(
                    repo, store, new PaperSessionReferences(), new PaperProperties())
                .remove(p.id(), true, op));
    assertTrue(store.exists(p.id(), "originals", "pdf"));
    assertTrue(repo.find(p.id()).isPresent());
  }

  @Test
  void shellDispatchPreservesArguments() {
    var conversations = mock(dev.mikoto2000.rei.application.session.ShellConversationService.class);
    var command = new picocli.CommandLine(new PaperCommand(conversations));
    assertEquals(0, command.execute("search", "GUI agent", "--since", "2025", "--limit", "10"));
    verify(conversations).submit("/paper search \"GUI agent\" --since 2025 --limit 10");
  }

  @Test
  void differentDoiPreventsTitleCollision() {
    var repo =
        new SqlitePaperRepository(new DriverManagerDataSource("jdbc:sqlite:" + dir.resolve("db")));
    var op = PaperOperation.local("s");
    var a =
        new Paper(
            null,
            "Title",
            List.of("Author"),
            null,
            2025,
            null,
            "10.1/a",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
    var b =
        new Paper(
            null,
            "Title",
            List.of("Author"),
            null,
            2025,
            null,
            "10.1/b",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of());
    assertNotEquals(repo.save(a, op).id(), repo.save(b, op).id());
  }

  @Test
  void parserRejectsUnknownOptionsAsDomainError() {
    assertThrows(
        PaperException.class,
        () -> PaperCommandRequest.parse(new String[] {"search", "GUI", "--bogus"}));
  }
}
