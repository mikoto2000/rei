package dev.mikoto2000.rei.episode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import dev.mikoto2000.rei.conversation.ConversationTurnStore;

class EpisodeProcessorTest {
  EpisodeRepository repository=mock(EpisodeRepository.class);
  ConversationTurnStore turns=mock(ConversationTurnStore.class);
  EpisodeExtractor extractor=mock(EpisodeExtractor.class);
  Episode episode(Episode.Evidence evidence,String run,String speaker) {
    return new Episode("e","p","s","v",Instant.EPOCH.toString(),Episode.Status.UNVERIFIED,"CLI","summary",
        List.of(new Episode.Claim("reason","isolation",evidence,run,speaker)),.8);
  }
  @Test void rejectsFabricatedSourceAndDoesNotAdvanceCheckpoint() {
    var batch=List.of(new ConversationTurnStore.Turn("r","choose CLI",ConversationTurnStore.Status.COMPLETED,"proposal",Instant.EPOCH));
    when(extractor.extract(eq("s"),eq("p"),eq(batch),any(),any())).thenReturn(List.of(episode(Episode.Evidence.UNVERIFIED,"invented","assistant")));
    assertThrows(IllegalArgumentException.class,()->new EpisodeProcessor(repository,extractor).process("s","p",0,1,batch,null));
    verify(repository,never()).saveBatch(anyString(),anyLong(),anyLong(),anyList());
  }
  @Test void preservesProposalsAndCommitsEmptyExtractionForSmallTalk() {
    var batch=List.of(new ConversationTurnStore.Turn("r","hello",ConversationTurnStore.Status.COMPLETED,"hello",Instant.EPOCH));
    when(extractor.extract(eq("s"),eq("p"),eq(batch),any(),any())).thenReturn(List.of());
    new EpisodeProcessor(repository,extractor).process("s","p",0,1,batch,null);
    verify(repository).saveBatch("s",0,1,List.of());
  }
  @Test void assistantCannotBecomeUserDecision() {
    assertThrows(IllegalArgumentException.class,()->episode(Episode.Evidence.USER_EXPLICIT,"r","assistant"));
    assertThrows(IllegalArgumentException.class,()->episode(Episode.Evidence.TOOL_OBSERVED,"r","assistant"));
  }
  @Test void acceptsMultipleTurnsAndKeepsAlternativeAndUnknown() {
    var batch=List.of(new ConversationTurnStore.Turn("r","choose CLI",ConversationTurnStore.Status.COMPLETED,"proposal",Instant.EPOCH),
        new ConversationTurnStore.Turn("r2","follow up",ConversationTurnStore.Status.COMPLETED,"pending",Instant.EPOCH));
    var e=episode(Episode.Evidence.MODEL_PROPOSAL,"r","assistant");
    when(extractor.extract(eq("s"),eq("p"),eq(batch),any(),any())).thenReturn(List.of(e));
    new EpisodeProcessor(repository,extractor).process("s","p",0,2,batch,null);
    verify(repository).saveBatch("s",0,2,List.of(e));
  }
  @Test void modelProposalCannotMarkAnEpisodeCompleted() {
    var batch=List.of(new ConversationTurnStore.Turn("r","consider CLI",ConversationTurnStore.Status.COMPLETED,"proposal",Instant.EPOCH));
    var proposed=episode(Episode.Evidence.MODEL_PROPOSAL,"r","assistant");
    var completed=new Episode(proposed.id(),proposed.projectId(),proposed.sessionId(),proposed.revision(),proposed.occurredAt(),Episode.Status.COMPLETED,proposed.title(),proposed.summary(),proposed.claims(),proposed.confidence());
    when(extractor.extract(eq("s"),eq("p"),eq(batch),any(),any())).thenReturn(List.of(completed));
    assertThrows(IllegalArgumentException.class,()->new EpisodeProcessor(repository,extractor).process("s","p",0,1,batch,null));
  }
}
