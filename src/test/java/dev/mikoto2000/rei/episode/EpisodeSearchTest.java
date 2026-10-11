package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
class EpisodeSearchTest {
  @Test void deletedSourcesDoNotResurrectSavedSummaryOrClaims() {
    var repository=org.mockito.Mockito.mock(EpisodeRepository.class);
    var turns=org.mockito.Mockito.mock(dev.mikoto2000.rei.conversation.ConversationTurnStore.class);
    var episode=new Episode("e","p","s","v",java.time.Instant.EPOCH.toString(),Episode.Status.COMPLETED,"private title","deleted text",
        List.of(new Episode.Claim("reason","private reason",Episode.Evidence.UNVERIFIED,"r","assistant")),.7);
    org.mockito.Mockito.when(repository.revisions("e","p")).thenReturn(List.of(episode));
    org.mockito.Mockito.when(turns.findRun("s","r")).thenReturn(java.util.Optional.empty());
    var service=new EpisodeSearchService(repository,null,null,turns,null);
    String detail=service.detail("e","p").toString();
    assertTrue(detail.contains("UNAVAILABLE"));assertFalse(detail.contains("private"));assertFalse(detail.contains("deleted text"));
    assertEquals("",service.sources("e","p").getFirst().get("text"));
  }
  @Test void fusedResultsAreBoundedAndDeduplicated() {
    var item=new EpisodeSearchService.Hit("episode","e","p","CLI","UNVERIFIED",0);
    var result=EpisodeSearchService.fuse(List.of(List.of(item,item),List.of(item)),1,500);
    assertEquals(1,result.size());assertEquals("e",result.getFirst().id());assertTrue(result.getFirst().score()>0);
  }
  @Test void totalTextBudgetIsEnforced() {
    var item=new EpisodeSearchService.Hit("episode","e","p","CLI".repeat(200),"UNVERIFIED",0);
    assertTrue(EpisodeSearchService.fuse(List.of(List.of(item)),5,20).isEmpty());
  }
  @Test void inaccessibleSourceReturnsStatusInsteadOfSavedText() {
    var repository=org.mockito.Mockito.mock(EpisodeRepository.class);
    var turns=org.mockito.Mockito.mock(dev.mikoto2000.rei.conversation.ConversationTurnStore.class);
    var episode=new Episode("e","p","s","v",java.time.Instant.EPOCH.toString(),Episode.Status.UNVERIFIED,"private title","private text",
        List.of(new Episode.Claim("reason","private reason",Episode.Evidence.UNVERIFIED,"r","assistant")),.7);
    org.mockito.Mockito.when(repository.revisions("e","p")).thenReturn(List.of(episode));
    org.mockito.Mockito.when(turns.findRun("s","r")).thenThrow(new IllegalStateException("Cannot read source"));
    var service=new EpisodeSearchService(repository,null,null,turns,null);
    assertTrue(service.detail("e","p").toString().contains("UNAVAILABLE"));
    assertEquals("",service.sources("e","p").getFirst().get("text"));
  }
}
