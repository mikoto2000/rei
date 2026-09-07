package dev.mikoto2000.rei.core.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import dev.mikoto2000.rei.search.SearchKnowledgeService;
import dev.mikoto2000.rei.ui.shell.RootCommand;
import dev.mikoto2000.rei.vectordocument.VectorDocumentProperties;
import dev.mikoto2000.rei.vectordocument.VectorDocumentRepository;
import dev.mikoto2000.rei.vectordocument.VectorDocumentService;
import dev.mikoto2000.rei.vectorstore.DisabledVectorStore;
import dev.mikoto2000.rei.websearch.WebSearchContext;
import dev.mikoto2000.rei.websearch.WebSearchOrchestrator;
import picocli.CommandLine;

class EmbeddingDisabledTest {
  @Test
  void briefingWithEventsWorksWithoutVectorDatabase() throws Exception {
    var calendar = mock(dev.mikoto2000.rei.googlecalendar.GoogleCalendarService.class);
    var tasks = mock(dev.mikoto2000.rei.task.TaskService.class);
    var narrator = mock(dev.mikoto2000.rei.briefing.BriefingNarrator.class);
    var interests = mock(dev.mikoto2000.rei.interest.InterestUpdateService.class);
    var feeds = mock(dev.mikoto2000.rei.feed.FeedService.class);
    var date = java.time.LocalDate.of(2026, 9, 7);
    when(calendar.listEventsForDate(date)).thenReturn(List.of(
        new dev.mikoto2000.rei.googlecalendar.GoogleCalendarEventSummary(
            "event", "Design meeting", "2026-09-07T09:00:00+09:00", "2026-09-07T10:00:00+09:00", "", "confirmed")));
    when(narrator.narrate(org.mockito.ArgumentMatchers.any())).thenReturn(
        new dev.mikoto2000.rei.briefing.BriefingNarration("overview", List.of(), List.of()));
    var briefing = new dev.mikoto2000.rei.briefing.BriefingService(calendar, tasks,
        new DisabledVectorStore(), narrator, interests, feeds, new dev.mikoto2000.rei.feed.FeedProperties(20))
        .briefingFor(date);
    assertThat(briefing.relatedDocuments()).isEmpty();
    assertThat(briefing.overview()).isEqualTo("overview");
    verify(narrator).narrate(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void disabledContextNeedsNeitherEmbeddingModelNorDataSourceAndKeepsWebSearch() {
    new ApplicationContextRunner()
        .withUserConfiguration(VectorStoreConfiguration.class)
        .withPropertyValues("rei.embedding.enabled=false")
        .run(context -> {
          assertThat(context).hasNotFailed().hasSingleBean(VectorStore.class);
          DisabledVectorStore store = context.getBean(DisabledVectorStore.class);
          assertThat(context.getBean(VectorDocumentRepository.class)).isSameAs(store);
          assertThat(store.getNativeClient()).isEmpty();
          VectorDocumentService documents = new VectorDocumentService(store, store, new VectorDocumentProperties(512, 0));
          WebSearchOrchestrator web = mock(WebSearchOrchestrator.class);
          WebSearchContext webResult = WebSearchContext.primaryOnly(List.of());
          when(web.search("query", 3)).thenReturn(webResult);
          var result = new SearchKnowledgeService(documents, web).search("query", 5, 3, null, null);
          assertThat(result.vectorResults()).isEmpty();
          assertThat(result.webContext()).isSameAs(webResult);
          verify(web).search("query", 3);
          assertThrows(IllegalStateException.class, () -> store.deleteBySource("docs/a.md"));
          assertThrows(IllegalStateException.class, () -> store.add(List.of()));
        });
  }

  @Test
  void disabledEmbedIsRemovedFromParsingAndHelp() {
    RootCommand root = new RootCommand();
    ReflectionTestUtils.setField(root, "embeddingEnabled", false);
    CommandLine command = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    command.addSubcommand("embed", CommandLine.Model.CommandSpec.create());
    command.addSubcommand("search", CommandLine.Model.CommandSpec.create());
    root.configureCommands(command);
    assertThat(command.getSubcommands()).containsKey("search").doesNotContainKey("embed");
    assertThat(command.getUsageMessage()).doesNotContain("embed");
    assertThrows(CommandLine.UnmatchedArgumentException.class, () -> command.parseArgs("embed", "list"));
  }

  @Test
  void embeddingCommandRemainsEnabledByDefault() {
    CommandLine command = new CommandLine(CommandLine.Model.CommandSpec.create().name("rei"));
    command.addSubcommand("embed", CommandLine.Model.CommandSpec.create());
    new RootCommand().configureCommands(command);
    assertThat(command.getSubcommands()).containsKey("embed");
  }
}
