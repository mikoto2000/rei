package dev.mikoto2000.rei.episode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.mikoto2000.rei.conversation.ConversationHistorySearchService;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EpisodePagingServiceTest {
  @Test
  void exactOldRevisionSourcesArePagedAndDeletedSourcesStayHidden() {
    var repo = mock(EpisodeRepository.class);
    var turns = mock(ConversationTurnStore.class);
    var service =
        new EpisodeSearchService(
            repo,
            mock(dev.mikoto2000.rei.memory.service.MemoryRepository.class),
            mock(ConversationHistorySearchService.class),
            turns,
            mock(dev.mikoto2000.rei.workcontext.WorkContextService.class));
    var claims = new ArrayList<Episode.Claim>();
    for (int i = 0; i < 10; i++)
      claims.add(
          new Episode.Claim(
              "reason", "proposal", Episode.Evidence.MODEL_PROPOSAL, "r" + i, "assistant"));
    var e =
        new Episode(
            "e",
            "p",
            "s",
            "old",
            "2026-10-11T00:00:00Z",
            Episode.Status.WITHDRAWN,
            "old",
            "old summary",
            claims,
            .5);
    when(repo.revision("e", "p", "old")).thenReturn(Optional.of(e));
    var first = service.sourcesPage("e", "p", "old", 0);
    assertEquals(8, ((List<?>) first.get("sources")).size());
    assertEquals(8, first.get("nextOffset"));
    var last = service.sourcesPage("e", "p", "old", 8);
    assertEquals(2, ((List<?>) last.get("sources")).size());
    assertNull(last.get("nextOffset"));
    assertFalse(last.toString().contains("proposal"));
    verify(turns, times(1)).findRun("s", "r0");
    assertThrows(IllegalArgumentException.class, () -> service.sourcesPage("e", "p", "old", 33));
    assertThrows(
        IllegalArgumentException.class, () -> service.sourcesPage("e", "foreign", "old", 0));
  }

  @Test
  void revisionPageExposesCursorWithoutReadingUnboundedHistory() {
    var repo = mock(EpisodeRepository.class);
    var service =
        new EpisodeSearchService(
            repo,
            mock(dev.mikoto2000.rei.memory.service.MemoryRepository.class),
            mock(ConversationHistorySearchService.class),
            mock(ConversationTurnStore.class),
            mock(dev.mikoto2000.rei.workcontext.WorkContextService.class));
    when(repo.revisionPage("e", "p", "v3", 3))
        .thenReturn(
            new EpisodeRepository.RevisionPage(
                List.of(EpisodeRevisionPagingTest.episode("v2")), "v2"));
    assertEquals("v2", service.detailPage("e", "p", "v3").get("nextCursor"));
    verify(repo, never()).revisions(anyString(), anyString());
  }
}
