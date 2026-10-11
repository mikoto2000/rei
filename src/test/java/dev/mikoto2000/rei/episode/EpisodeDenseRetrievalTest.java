package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.conversation.*;
class EpisodeDenseRetrievalTest {
 final String project="11111111-1111-1111-1111-111111111111";
 EpisodeRepository repo=mock(EpisodeRepository.class);EpisodeDenseIndex dense=mock(EpisodeDenseIndex.class);
 ConversationTurnStore turns=mock(ConversationTurnStore.class);
 EpisodeSearchService service=new EpisodeSearchService(repo,mock(dev.mikoto2000.rei.memory.service.MemoryRepository.class),mock(ConversationHistorySearchService.class),turns,mock(dev.mikoto2000.rei.workcontext.WorkContextService.class));
 HistorySearchRequest request(){return new HistorySearchRequest("semantic synonym",project,null,HistorySearchScope.CURRENT_PROJECT_ONLY,"all",null,null,null,8);}
 EpisodeDenseRetrievalTest(){service.setDense(dense);}
 @Test void providerFailureFallsBackButBudgetExhaustionStillStopsTheRun() {
  when(dense.search(anyString(),eq(project),anyInt(),any())).thenThrow(new IllegalStateException("provider unavailable"));
  assertTrue(service.search(request(),"known topic").isEmpty());
  var stopped=new dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.Reason.TOKEN_BUDGET_EXCEEDED);
  when(dense.search(anyString(),eq(project),anyInt(),any())).thenThrow(stopped);
  assertSame(stopped,assertThrows(dev.mikoto2000.rei.core.stagnation.ExecutionStoppedException.class,()->service.search(request(),"known topic")));
 }
 @Test void denseAndLexicalDuplicatesFuseIntoOneEvidenceCheckedCandidate() {
  var e=new Episode("e",project,"s","v","2026-10-11T00:00:00Z",Episode.Status.UNVERIFIED,"design","summary",List.of(new Episode.Claim("reason","proposal",Episode.Evidence.MODEL_PROPOSAL,"r","assistant")),.5);
  when(repo.search(anyString(),eq(project),anyInt(),any())).thenReturn(List.of(e));when(dense.search(anyString(),eq(project),anyInt(),any())).thenReturn(List.of(e));
  when(turns.findRun("s","r")).thenReturn(Optional.of(new ConversationTurnStore.Turn("r","request",ConversationTurnStore.Status.COMPLETED,"proposal",java.time.Instant.EPOCH)));
  assertEquals(1,service.search(request(),"known topic").size());
  when(turns.findRun("s","r")).thenReturn(Optional.empty());assertTrue(service.search(request(),"known topic").isEmpty());
 }
 @Test void disabledMemoryNeverCallsDenseOrLexicalProviders() {
  service.setMemoryProperties(new dev.mikoto2000.rei.memory.configuration.MemoryProperties(false,0,0,0,0,0,0,null));
  service.search(request(),"known topic");verifyNoInteractions(repo,dense,turns);
 }
}
