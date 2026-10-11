package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.*;
import dev.mikoto2000.rei.conversation.*;
import dev.mikoto2000.rei.memory.service.MemoryRepository;
import dev.mikoto2000.rei.workcontext.WorkContextService;
class EpisodeFederatedSearchTest {
  final String project="11111111-1111-1111-1111-111111111111";
  EpisodeRepository episodes=mock(EpisodeRepository.class);MemoryRepository memory=mock(MemoryRepository.class);
  ConversationHistorySearchService history=mock(ConversationHistorySearchService.class);
  ConversationTurnStore turns=mock(ConversationTurnStore.class);WorkContextService work=mock(WorkContextService.class);
  EpisodeSearchService service=new EpisodeSearchService(episodes,memory,history,turns,work);
  HistorySearchRequest request(){return new HistorySearchRequest("CLI",project,null,HistorySearchScope.CURRENT_PROJECT_ONLY,"all",null,null,null,8);}
  @BeforeEach void setup(){when(work.current(project)).thenReturn(Optional.empty());}
  ConversationSearchResult raw(){return new ConversationSearchResult("s","chat","user",Instant.EPOCH.toString(),"CLI pending","CLI pending",project,"Project A",HistoryRetrievalPolicy.LOCAL_BOUNDARY,1);}
  @Test void rawConversationsAreSearchableWithoutAnySleepOrEpisode() {
    when(history.search(any(HistorySearchRequest.class))).thenReturn(List.of(raw()));
    var hits=service.search(request(),null);
    assertEquals("conversation",hits.getFirst().kind());assertEquals("CLI pending",hits.getFirst().summary());
    verify(episodes,never()).checkpoint(anyString());
  }
  @Test void candidatesOmitClaimBodiesUntilDetailIsRequested() {
    var episode=new Episode("e",project,"s","v",Instant.EPOCH.toString(),Episode.Status.UNVERIFIED,"CLI","design proposal",
        List.of(new Episode.Claim("reason","detailed isolation reasoning",Episode.Evidence.MODEL_PROPOSAL,"r","assistant")),.8);
    when(episodes.search(anyString(),eq(project),anyInt(),any(EpisodeTimeRange.class))).thenReturn(List.of(episode));
    when(episodes.revisions("e",project)).thenReturn(List.of(episode));
    when(turns.findRun("s","r")).thenReturn(Optional.of(new ConversationTurnStore.Turn("r","CLI",ConversationTurnStore.Status.COMPLETED,"proposal",Instant.EPOCH)));
    assertFalse(service.search(request(),null).toString().contains("detailed isolation reasoning"));
    assertTrue(service.detail("e",project).toString().contains("detailed isolation reasoning"));
  }
  @Test void disablingMemoryLeavesRawHistoryButNeverReadsSavedMemory() {
    when(history.search(any(HistorySearchRequest.class))).thenReturn(List.of(raw()));
    service.setMemoryProperties(new dev.mikoto2000.rei.memory.configuration.MemoryProperties(false,0,0,0,0,0,0,null));
    assertEquals("conversation",service.search(request(),null).getFirst().kind());
    verifyNoInteractions(episodes,memory,turns);
  }
  @Test void ambiguousQuestionUsesActualRecentSessionTopic() {
    var old=new ConversationTurnStore.Turn("previous","CLI independent process",ConversationTurnStore.Status.COMPLETED,"proposal",Instant.EPOCH);
    var current=new ConversationTurnStore.Turn("current","why that decision",ConversationTurnStore.Status.RUNNING,null,Instant.EPOCH.plusSeconds(1));
    when(turns.turnCount("s")).thenReturn(2L);when(turns.readRange("s",0,3)).thenReturn(List.of(old,current));
    var request=new HistorySearchRequest("why that decision",project,null,HistorySearchScope.CURRENT_PROJECT_ONLY,"all",null,null,null,8);
    try(var scope=dev.mikoto2000.rei.core.chat.AgentRunScope.open(new dev.mikoto2000.rei.core.chat.AgentRunContext("current","s",java.nio.file.Path.of("."),project))) {
      service.search(request,null);
    }
    verify(history,atLeastOnce()).search(argThat(r->r.query().contains("CLI")));
  }
}
